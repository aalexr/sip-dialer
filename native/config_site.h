/* PJSIP build configuration for SipDialer (copied to pjlib/include/pj/config_site.h). */

#define PJ_CONFIG_ANDROID 1
#include <pj/config_site_sample.h>

/* libsipdialer.so defines JNI_OnLoad itself and hands the JVM to PJSIP. */
#define PJ_JNI_HAS_JNI_ONLOAD 0

/* Audio only. */
#define PJMEDIA_HAS_VIDEO 0

/* AMR-NB and AMR-WB through Android MediaCodec (the phone's own codecs). The AMR
 * fmtp handling in and_aud_mediacodec.cpp is guarded by the AMR-NB switch, so keep
 * both on. */
#define PJMEDIA_HAS_AND_MEDIA_AMRNB 1
#define PJMEDIA_HAS_AND_MEDIA_AMRWB 1

/* Echo cancellation comes from the platform (VOICE_COMMUNICATION mic source). */
#define PJMEDIA_HAS_SPEEX_AEC 0

/* The Android sample config turns this off for very old devices. */
#undef PJ_HAS_FLOATING_POINT
#define PJ_HAS_FLOATING_POINT 1

/* Codecs we don't offer. */
#define PJMEDIA_HAS_L16_CODEC 0
#define PJMEDIA_HAS_GSM_CODEC 0
#define PJMEDIA_HAS_ILBC_CODEC 0
#define PJMEDIA_HAS_SPEEX_CODEC 0
#define PJMEDIA_HAS_G7221_CODEC 0

/* TLS via OpenSSL, SRTP via the bundled libsrtp. */
#define PJ_HAS_SSL_SOCK 1
#define PJMEDIA_HAS_SRTP 1

/* PJ_TODO() otherwise warns. */
#define PJ_TODO(x)
