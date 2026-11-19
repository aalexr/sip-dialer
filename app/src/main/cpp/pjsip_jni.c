/*
 * Minimal JNI bridge between app.sipdialer.sip.PjNative (Kotlin) and the pjsua C API.
 *
 * Design:
 *  - One account, at most PJSUA_MAX_CALLS calls.
 *  - Kotlin identifies calls by a "token" stored as pjsua call user data. pjsua call ids are
 *    recycled; tokens are not, so a stale action from the UI can never hit a newer call.
 *    Outgoing calls get their token from Kotlin, incoming calls from INCOMING_TOKEN_BASE up.
 *  - Every pjsua callback is forwarded to a PjListener object. The Kotlin side posts it to
 *    the main thread, so it must not call back into native code synchronously.
 *  - Strings that come off the network are handed over as byte[] (UTF-8): JNI's
 *    NewStringUTF only accepts "modified UTF-8" and aborts on e.g. emoji.
 */
#include <jni.h>
#include <android/log.h>
#include <pjsua-lib/pjsua.h>
#include <stdint.h>
#include <string.h>

#define TAG "pjsip"
#define THIS_FILE "pjsip_jni.c"
#define INCOMING_TOKEN_BASE 0x40000000

enum { TP_UDP = 0, TP_TCP = 1, TP_TLS = 2, TP_COUNT = 3 };

static JavaVM *g_vm;
static jobject g_listener; /* global ref */
static jmethodID m_on_reg_state, m_on_call_state, m_on_media_state;
static pjsua_acc_id g_acc = PJSUA_INVALID_ID;
static pjsua_transport_id g_tp[TP_COUNT] = {PJSUA_INVALID_ID, PJSUA_INVALID_ID, PJSUA_INVALID_ID};
static volatile int g_started;
static int g_incoming_seq = INCOMING_TOKEN_BASE;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
    pj_jni_set_jvm(vm);
    return JNI_VERSION_1_6;
}

/* ---- Threads ---------------------------------------------------------------------------- */

/* pjsua must only be called from threads registered with pjlib. */
static void register_thread(void) {
    static __thread pj_thread_desc desc;
    static __thread pj_thread_t *thread;
    if (!pj_thread_is_registered()) {
        pj_bzero(desc, sizeof(desc));
        pj_thread_register("jni", desc, &thread);
    }
}

/* Gets a JNIEnv for the current (pjsip) thread, attaching it if needed. */
static JNIEnv *callback_env(int *attached) {
    JNIEnv *env = NULL;
    *attached = 0;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return NULL;
        *attached = 1;
    }
    return env;
}

static void callback_done(JNIEnv *env, int attached) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}

/* ---- Helpers ---------------------------------------------------------------------------- */

static jbyteArray to_bytes(JNIEnv *env, const char *data, pj_ssize_t len) {
    if (len < 0) len = 0;
    jbyteArray arr = (*env)->NewByteArray(env, (jsize) len);
    if (arr && len > 0) (*env)->SetByteArrayRegion(env, arr, 0, (jsize) len, (const jbyte *) data);
    return arr;
}

static jbyteArray pj_to_bytes(JNIEnv *env, const pj_str_t *s) {
    return to_bytes(env, s->ptr, s->slen);
}

static jstring status_string(JNIEnv *env, pj_status_t status) {
    char buf[PJ_ERR_MSG_SIZE];
    pj_str_t s = pj_strerror(status, buf, sizeof(buf));
    /* pjlib error strings are plain ASCII. */
    char out[PJ_ERR_MSG_SIZE + 1];
    pj_size_t n = (pj_size_t) s.slen < sizeof(out) - 1 ? (pj_size_t) s.slen : sizeof(out) - 1;
    memcpy(out, s.ptr, n);
    out[n] = '\0';
    return (*env)->NewStringUTF(env, out);
}

/* Copies a Java string into a pool-backed pj_str_t. */
static pj_str_t jstr(JNIEnv *env, pj_pool_t *pool, jstring s) {
    pj_str_t out = {NULL, 0};
    if (!s) return out;
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    pj_strdup2_with_null(pool, &out, c);
    (*env)->ReleaseStringUTFChars(env, s, c);
    return out;
}

static int call_token(pjsua_call_id id) {
    return (int) (intptr_t) pjsua_call_get_user_data(id);
}

static pjsua_call_id find_call(int token) {
    pjsua_call_id ids[PJSUA_MAX_CALLS];
    unsigned count = PJ_ARRAY_SIZE(ids);
    if (pjsua_enum_calls(ids, &count) != PJ_SUCCESS) return PJSUA_INVALID_ID;
    for (unsigned i = 0; i < count; i++) {
        if (call_token(ids[i]) == token) return ids[i];
    }
    return PJSUA_INVALID_ID;
}

/* ---- Callbacks -------------------------------------------------------------------------- */

static void log_cb(int level, const char *data, int len) {
    int prio = level <= 1 ? ANDROID_LOG_ERROR
               : level == 2 ? ANDROID_LOG_WARN
               : level == 3 ? ANDROID_LOG_INFO
                            : ANDROID_LOG_DEBUG;
    /* pjlib log lines end with a newline; logcat adds its own. */
    while (len > 0 && (data[len - 1] == '\n' || data[len - 1] == '\r')) len--;
    __android_log_print(prio, TAG, "%.*s", len, data);
}

static void on_reg_state2(pjsua_acc_id acc_id, pjsua_reg_info *info) {
    (void) acc_id;
    int attached;
    JNIEnv *env = callback_env(&attached);
    if (!env) return;
    struct pjsip_regc_cbparam *p = info->cbparam;
    jbyteArray reason;
    if (p->status != PJ_SUCCESS) {
        /* No usable SIP response (DNS, TCP/TLS, timeout): describe the pjlib error. A final
         * SIP error response such as 403 arrives with status PJ_SUCCESS and its own reason. */
        char buf[PJ_ERR_MSG_SIZE];
        pj_str_t s = pj_strerror(p->status, buf, sizeof(buf));
        reason = pj_to_bytes(env, &s);
    } else {
        reason = pj_to_bytes(env, &p->reason);
    }
    (*env)->CallVoidMethod(env, g_listener, m_on_reg_state, (jint) p->code, reason,
                           (jint) (p->is_unreg ? 0 : p->expiration), (jboolean) (info->renew != 0));
    (*env)->DeleteLocalRef(env, reason);
    callback_done(env, attached);
}

static void report_call_state(pjsua_call_id call_id) {
    pjsua_call_info ci;
    if (pjsua_call_get_info(call_id, &ci) != PJ_SUCCESS) return;
    int attached;
    JNIEnv *env = callback_env(&attached);
    if (!env) return;
    jbyteArray remote = pj_to_bytes(env, &ci.remote_info);
    jbyteArray reason = pj_to_bytes(env, &ci.last_status_text);
    (*env)->CallVoidMethod(env, g_listener, m_on_call_state, (jint) call_token(call_id),
                           (jint) ci.state, (jboolean) (ci.role == PJSIP_ROLE_UAS),
                           (jint) ci.last_status, reason, remote);
    (*env)->DeleteLocalRef(env, remote);
    (*env)->DeleteLocalRef(env, reason);
    callback_done(env, attached);
}

static void on_incoming_call(pjsua_acc_id acc_id, pjsua_call_id call_id, pjsip_rx_data *rdata) {
    (void) acc_id;
    (void) rdata;
    pjsua_call_set_user_data(call_id, (void *) (intptr_t) g_incoming_seq++);
    if (g_incoming_seq < INCOMING_TOKEN_BASE) g_incoming_seq = INCOMING_TOKEN_BASE;
    pjsua_call_answer(call_id, 180, NULL, NULL);
    report_call_state(call_id);
}

static void on_call_state(pjsua_call_id call_id, pjsip_event *e) {
    (void) e;
    report_call_state(call_id);
}

static void on_call_media_state(pjsua_call_id call_id) {
    pjsua_call_info ci;
    if (pjsua_call_get_info(call_id, &ci) != PJ_SUCCESS) return;

    char codec[64] = "";
    int clock_rate = 0;
    pjsua_call_media_status status = PJSUA_CALL_MEDIA_NONE;
    for (unsigned i = 0; i < ci.media_cnt; i++) {
        if (ci.media[i].type != PJMEDIA_TYPE_AUDIO) continue;
        status = ci.media[i].status;
        if (status == PJSUA_CALL_MEDIA_ACTIVE || status == PJSUA_CALL_MEDIA_REMOTE_HOLD) {
            pjsua_conf_port_id slot = ci.media[i].stream.aud.conf_slot;
            pjsua_conf_connect(slot, 0);
            pjsua_conf_connect(0, slot);
        }
        pjsua_stream_info si;
        if (pjsua_call_get_stream_info(call_id, ci.media[i].index, &si) == PJ_SUCCESS &&
            si.type == PJMEDIA_TYPE_AUDIO) {
            pj_str_t name = si.info.aud.fmt.encoding_name;
            int n = (int) PJ_MIN((pj_ssize_t) sizeof(codec) - 1, name.slen);
            memcpy(codec, name.ptr, (size_t) n);
            codec[n] = '\0';
            clock_rate = (int) si.info.aud.fmt.clock_rate;
            PJ_LOG(3, (THIS_FILE, "Call %d audio: %s/%d, status %d", call_id, codec, clock_rate,
                       status));
        }
        break;
    }

    int attached;
    JNIEnv *env = callback_env(&attached);
    if (!env) return;
    jstring jcodec = (*env)->NewStringUTF(env, codec); /* codec names are ASCII */
    (*env)->CallVoidMethod(env, g_listener, m_on_media_state, (jint) call_token(call_id),
                           (jint) status, jcodec, (jint) clock_rate);
    (*env)->DeleteLocalRef(env, jcodec);
    callback_done(env, attached);
}

/* ---- Lifecycle -------------------------------------------------------------------------- */

#define JNI_FN(name) Java_app_sipdialer_sip_PjNative_##name

/** Returns null on success or an error message. */
/*
 * AMR / AMR-WB like a phone: bandwidth-efficient packing, AMR-WB at 12.65 kbit/s.
 *
 * pjsip's Android MediaCodec AMR codec defaults to octet-align=1 and offers only that. IMS
 * networks use bandwidth-efficient mode (the RFC 4867 default, no octet-align in the fmtp), and
 * the gateway passes carrier AMR-WB frames through to us unchanged when both legs use AMR-WB:
 * with octet-aligned on our side and bandwidth-efficient on the carrier's, neither end can
 * decode the other's frames and the call is silent both ways. Without the octet-align
 * parameter we offer (and answer) bandwidth-efficient; the codec takes the packing from the
 * negotiated fmtp when it opens.
 *
 * The default AMR-WB rate is 15.85 kbit/s (mode 4). Passed through unchanged, that breaks
 * a carrier leg restricted to mode-set=0,1,2 (common where calls interwork with circuit-
 * switched networks), so encode 12.65 kbit/s (mode 2), the rate phones use. AMR stays at its
 * 12.2 kbit/s maximum (mode 7).
 */
static void amr_defaults(void) {
    static const struct {
        const char *id;
        unsigned bps;
    } amr[] = {{"AMR-WB/16000/1", 12650}, {"AMR/8000/1", 12200}};
    for (unsigned i = 0; i < PJ_ARRAY_SIZE(amr); i++) {
        pj_str_t id = pj_str((char *) amr[i].id);
        pjmedia_codec_param param;
        if (pjsua_codec_get_param(&id, &param) != PJ_SUCCESS) continue; /* no such codec */
        unsigned n = 0;
        for (unsigned j = 0; j < param.setting.dec_fmtp.cnt; j++) {
            if (pj_stricmp2(&param.setting.dec_fmtp.param[j].name, "octet-align") != 0) {
                param.setting.dec_fmtp.param[n++] = param.setting.dec_fmtp.param[j];
            }
        }
        param.setting.dec_fmtp.cnt = (pj_uint8_t) n;
        param.info.avg_bps = amr[i].bps;
        pj_status_t st = pjsua_codec_set_param(&id, &param);
        __android_log_print(st == PJ_SUCCESS ? ANDROID_LOG_INFO : ANDROID_LOG_WARN, TAG,
                            "%s: bandwidth-efficient, %u bit/s (%d)", amr[i].id, amr[i].bps, st);
    }
}

JNIEXPORT jstring JNICALL JNI_FN(start)(JNIEnv *env, jobject thiz, jobject listener,
                                        jstring user_agent, jboolean debug, jstring ca_path) {
    (void) thiz;
    if (g_started) return NULL;

    jclass cls = (*env)->GetObjectClass(env, listener);
    m_on_reg_state = (*env)->GetMethodID(env, cls, "onRegState", "(I[BIZ)V");
    m_on_call_state = (*env)->GetMethodID(env, cls, "onCallState", "(IIZI[B[B)V");
    m_on_media_state = (*env)->GetMethodID(env, cls, "onMediaState", "(IILjava/lang/String;I)V");
    if (!m_on_reg_state || !m_on_call_state || !m_on_media_state) {
        return (*env)->NewStringUTF(env, "PjListener methods not found");
    }
    g_listener = (*env)->NewGlobalRef(env, listener);

    pj_status_t status = pjsua_create();
    if (status != PJ_SUCCESS) return status_string(env, status);
    pj_pool_t *pool = pjsua_pool_create("jni-start", 512, 512);

    pjsua_config cfg;
    pjsua_config_default(&cfg);
    cfg.user_agent = jstr(env, pool, user_agent);
    cfg.max_calls = PJSUA_MAX_CALLS;
    cfg.thread_cnt = 1;
    cfg.cb.on_reg_state2 = &on_reg_state2;
    cfg.cb.on_incoming_call = &on_incoming_call;
    cfg.cb.on_call_state = &on_call_state;
    cfg.cb.on_call_media_state = &on_call_media_state;

    pjsua_logging_config log;
    pjsua_logging_config_default(&log);
    log.level = debug ? 5 : 3;
    log.console_level = log.level;
    log.msg_logging = debug ? PJ_TRUE : PJ_FALSE;
    log.decor = PJ_LOG_HAS_SENDER | PJ_LOG_HAS_NEWLINE;
    log.cb = &log_cb;

    pjsua_media_config med;
    pjsua_media_config_default(&med);
    /* 16 kHz: AMR-WB and G.722 run without resampling; Opus is limited to wideband. */
    med.clock_rate = 16000;
    med.snd_clock_rate = 16000;
    /* The Android audio device records with the VOICE_COMMUNICATION source, which brings the
     * platform's echo canceller; a second software one only makes things worse. */
    med.ec_tail_len = 0;
    med.no_vad = PJ_TRUE;

    status = pjsua_init(&cfg, &log, &med);
    if (status != PJ_SUCCESS) goto fail;

    pjsua_transport_config tc;
    pjsua_transport_config_default(&tc);
    tc.port = 0; /* ephemeral */
    status = pjsua_transport_create(PJSIP_TRANSPORT_UDP, &tc, &g_tp[TP_UDP]);
    if (status != PJ_SUCCESS) goto fail;
    status = pjsua_transport_create(PJSIP_TRANSPORT_TCP, &tc, &g_tp[TP_TCP]);
    if (status != PJ_SUCCESS) goto fail;

    pjsua_transport_config_default(&tc);
    tc.port = 0;
    tc.tls_setting.ca_list_path = jstr(env, pool, ca_path);
    tc.tls_setting.verify_server = PJ_TRUE;
    status = pjsua_transport_create(PJSIP_TRANSPORT_TLS, &tc, &g_tp[TP_TLS]);
    if (status != PJ_SUCCESS) goto fail;

    status = pjsua_start();
    if (status != PJ_SUCCESS) goto fail;

    amr_defaults();

    pj_pool_release(pool);
    g_started = 1;
    return NULL;

fail: {
    jstring msg = status_string(env, status);
    pj_pool_release(pool);
    pjsua_destroy();
    return msg;
}
}

/* ---- Codecs ----------------------------------------------------------------------------- */

/**
 * Enables exactly the codecs whose id starts with one of [prefixes] (e.g. "AMR-WB/"), in
 * that order, and disables every other one. Returns the resulting list, best first.
 */
JNIEXPORT jobjectArray JNICALL JNI_FN(setCodecOrder)(JNIEnv *env, jobject thiz,
                                                    jobjectArray prefixes) {
    (void) thiz;
    register_thread();
    pjsua_codec_info codecs[64];
    unsigned count = PJ_ARRAY_SIZE(codecs);
    pjsua_enum_codecs(codecs, &count);

    jsize n = (*env)->GetArrayLength(env, prefixes);
    for (unsigned i = 0; i < count; i++) {
        int prio = 0;
        for (jsize j = 0; j < n; j++) {
            jstring p = (jstring) (*env)->GetObjectArrayElement(env, prefixes, j);
            const char *c = (*env)->GetStringUTFChars(env, p, NULL);
            size_t len = strlen(c);
            int match = (size_t) codecs[i].codec_id.slen >= len &&
                        pj_ansi_strnicmp(codecs[i].codec_id.ptr, c, len) == 0;
            (*env)->ReleaseStringUTFChars(env, p, c);
            (*env)->DeleteLocalRef(env, p);
            if (match) {
                prio = PJMEDIA_CODEC_PRIO_HIGHEST - (int) j;
                break;
            }
        }
        pjsua_codec_set_priority(&codecs[i].codec_id, (pj_uint8_t) prio);
    }

    count = PJ_ARRAY_SIZE(codecs);
    pjsua_enum_codecs(codecs, &count); /* sorted by priority */
    jobjectArray out = (*env)->NewObjectArray(env, (jsize) count,
                                              (*env)->FindClass(env, "java/lang/String"), NULL);
    for (unsigned i = 0; i < count; i++) {
        char buf[96];
        pj_ansi_snprintf(buf, sizeof(buf), "%.*s prio=%d", (int) codecs[i].codec_id.slen,
                         codecs[i].codec_id.ptr, codecs[i].priority);
        jstring s = (*env)->NewStringUTF(env, buf);
        (*env)->SetObjectArrayElement(env, out, (jsize) i, s);
        (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

/* ---- Account ---------------------------------------------------------------------------- */

/**
 * Replaces the account. [id] is the From/To identity ("Name" <sip:user@domain>),
 * [registrar] the REGISTER request URI (sip:domain), [proxy] the outbound proxy every
 * request is routed through (<sip:host;transport=tls;lr>). Returns null or an error.
 */
JNIEXPORT jstring JNICALL JNI_FN(setAccount)(JNIEnv *env, jobject thiz, jstring id,
                                             jstring registrar, jstring proxy, jstring auth_user,
                                             jstring password, jint transport) {
    (void) thiz;
    if (!g_started) return (*env)->NewStringUTF(env, "SIP stack not running");
    if (transport < 0 || transport >= TP_COUNT) return (*env)->NewStringUTF(env, "Bad transport");
    register_thread();

    if (g_acc != PJSUA_INVALID_ID) {
        pjsua_acc_del(g_acc);
        g_acc = PJSUA_INVALID_ID;
    }

    pj_pool_t *pool = pjsua_pool_create("jni-acc", 512, 512);
    pjsua_acc_config ac;
    pjsua_acc_config_default(&ac);
    ac.id = jstr(env, pool, id);
    ac.reg_uri = jstr(env, pool, registrar);
    ac.proxy_cnt = 1;
    ac.proxy[0] = jstr(env, pool, proxy);
    ac.transport_id = g_tp[transport];
    ac.cred_count = 1;
    ac.cred_info[0].realm = pj_str("*");
    ac.cred_info[0].scheme = pj_str("digest");
    ac.cred_info[0].username = jstr(env, pool, auth_user);
    ac.cred_info[0].data_type = PJSIP_CRED_DATA_PLAIN_PASSWD;
    ac.cred_info[0].data = jstr(env, pool, password);

    pj_status_t status = pjsua_acc_add(&ac, PJ_TRUE, &g_acc);
    pj_pool_release(pool);
    if (status != PJ_SUCCESS) {
        g_acc = PJSUA_INVALID_ID;
        return status_string(env, status);
    }
    return NULL;
}

JNIEXPORT void JNICALL JNI_FN(removeAccount)(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    if (!g_started || g_acc == PJSUA_INVALID_ID) return;
    register_thread();
    pjsua_acc_del(g_acc); /* sends an un-REGISTER */
    g_acc = PJSUA_INVALID_ID;
}

JNIEXPORT void JNICALL JNI_FN(refreshRegistration)(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    if (!g_started || g_acc == PJSUA_INVALID_ID) return;
    register_thread();
    pjsua_acc_set_registration(g_acc, PJ_TRUE);
}

/** The default network changed: rebind transports, re-register, re-INVITE active calls. */
JNIEXPORT void JNICALL JNI_FN(ipChanged)(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    if (!g_started) return;
    register_thread();
    pjsua_ip_change_param param;
    pjsua_ip_change_param_default(&param);
    pjsua_handle_ip_change(&param);
}

/* ---- Calls ------------------------------------------------------------------------------ */

/** Starts a call tagged with [token]. Returns null or an error message. */
JNIEXPORT jstring JNICALL JNI_FN(makeCall)(JNIEnv *env, jobject thiz, jstring uri, jint token) {
    (void) thiz;
    if (!g_started || g_acc == PJSUA_INVALID_ID) {
        return (*env)->NewStringUTF(env, "Set up a SIP account first");
    }
    register_thread();
    pj_pool_t *pool = pjsua_pool_create("jni-call", 256, 256);
    pj_str_t dst = jstr(env, pool, uri);
    pjsua_call_setting opt;
    pjsua_call_setting_default(&opt);
    opt.vid_cnt = 0;
    pj_status_t status = pjsua_call_make_call(g_acc, &dst, &opt, (void *) (intptr_t) token, NULL,
                                              NULL);
    pj_pool_release(pool);
    return status == PJ_SUCCESS ? NULL : status_string(env, status);
}

JNIEXPORT void JNICALL JNI_FN(answer)(JNIEnv *env, jobject thiz, jint token) {
    (void) env;
    (void) thiz;
    if (!g_started) return;
    register_thread();
    pjsua_call_id id = find_call(token);
    if (id == PJSUA_INVALID_ID) return;
    pjsua_call_setting opt;
    pjsua_call_setting_default(&opt);
    opt.vid_cnt = 0;
    pjsua_call_answer2(id, &opt, 200, NULL, NULL);
}

/** Ends the call; an unanswered incoming call is declined with [code] (e.g. 603). */
JNIEXPORT void JNICALL JNI_FN(hangup)(JNIEnv *env, jobject thiz, jint token, jint code) {
    (void) env;
    (void) thiz;
    if (!g_started) return;
    register_thread();
    pjsua_call_id id = find_call(token);
    if (id != PJSUA_INVALID_ID) pjsua_call_hangup(id, (unsigned) code, NULL, NULL);
}

JNIEXPORT void JNICALL JNI_FN(setHold)(JNIEnv *env, jobject thiz, jint token, jboolean hold) {
    (void) env;
    (void) thiz;
    if (!g_started) return;
    register_thread();
    pjsua_call_id id = find_call(token);
    if (id == PJSUA_INVALID_ID) return;
    if (hold) {
        pjsua_call_set_hold(id, NULL);
    } else {
        pjsua_call_reinvite(id, PJSUA_CALL_UNHOLD, NULL);
    }
}

JNIEXPORT void JNICALL JNI_FN(sendDtmf)(JNIEnv *env, jobject thiz, jint token, jstring digits) {
    (void) thiz;
    if (!g_started) return;
    register_thread();
    pjsua_call_id id = find_call(token);
    if (id == PJSUA_INVALID_ID) return;
    pj_pool_t *pool = pjsua_pool_create("jni-dtmf", 64, 64);
    pj_str_t d = jstr(env, pool, digits);
    /* RFC 4733 telephone-event; fall back to SIP INFO if the remote didn't offer it. */
    pjsua_call_send_dtmf_param p;
    pjsua_call_send_dtmf_param_default(&p);
    p.digits = d;
    p.method = PJSUA_DTMF_METHOD_RFC2833;
    if (pjsua_call_send_dtmf(id, &p) != PJ_SUCCESS) {
        p.method = PJSUA_DTMF_METHOD_SIP_INFO;
        pjsua_call_send_dtmf(id, &p);
    }
    pj_pool_release(pool);
}

/** Mutes the microphone for all calls (slot 0 is the sound device). */
JNIEXPORT void JNICALL JNI_FN(setMicMuted)(JNIEnv *env, jobject thiz, jboolean muted) {
    (void) env;
    (void) thiz;
    if (!g_started) return;
    register_thread();
    pjsua_conf_adjust_rx_level(0, muted ? 0.0f : 1.0f);
}
