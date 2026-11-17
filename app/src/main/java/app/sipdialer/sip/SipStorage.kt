package app.sipdialer.sip

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists the SIP account (app-private SharedPreferences) and the call history (JSON). */
internal class SipStorage(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("sip_account", Context.MODE_PRIVATE)
    private val recentsFile = File(context.filesDir, "recents.json")

    fun loadAccount(): SipAccountConfig? {
        val username = prefs.getString("username", null) ?: return null
        val domain = prefs.getString("domain", null) ?: return null
        return SipAccountConfig(
            username = username,
            password = prefs.getString("password", "").orEmpty(),
            domain = domain,
            transport = runCatching { Transport.valueOf(prefs.getString("transport", "TLS")!!) }.getOrDefault(Transport.TLS),
            displayName = prefs.getString("displayName", "").orEmpty(),
            proxy = prefs.getString("proxy", "").orEmpty(),
            authUsername = prefs.getString("authUsername", "").orEmpty(),
        )
    }

    fun saveAccount(cfg: SipAccountConfig) {
        prefs
            .edit()
            .putString("username", cfg.username)
            .putString("password", cfg.password)
            .putString("domain", cfg.domain)
            .putString("transport", cfg.transport.name)
            .putString("displayName", cfg.displayName)
            .putString("proxy", cfg.proxy)
            .putString("authUsername", cfg.authUsername)
            .apply()
    }

    fun clearAccount() {
        prefs.edit().clear().apply()
    }

    fun loadRecents(): List<RecentCall> =
        runCatching {
            if (!recentsFile.exists()) return emptyList()
            val arr = JSONArray(recentsFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentCall(
                    remote = o.getString("remote"),
                    remoteUri = o.getString("remoteUri"),
                    displayName = o.optString("displayName").ifBlank { null },
                    direction = CallDirection.valueOf(o.getString("direction")),
                    missed = o.getBoolean("missed"),
                    startMs = o.getLong("startMs"),
                    durationSec = o.getInt("durationSec"),
                )
            }
        }.getOrElse {
            Log.w(TAG, "Could not read call history", it)
            emptyList()
        }

    fun saveRecents(recents: List<RecentCall>) {
        val arr = JSONArray()
        for (r in recents.take(MAX_RECENTS)) {
            arr.put(
                JSONObject()
                    .put("remote", r.remote)
                    .put("remoteUri", r.remoteUri)
                    .put("displayName", r.displayName.orEmpty())
                    .put("direction", r.direction.name)
                    .put("missed", r.missed)
                    .put("startMs", r.startMs)
                    .put("durationSec", r.durationSec),
            )
        }
        val tmp = File(recentsFile.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(recentsFile)
    }

    private companion object {
        const val TAG = "SipStorage"
        const val MAX_RECENTS = 500
    }
}
