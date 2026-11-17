package app.sipdialer.sip

import java.net.URLDecoder

/** A parsed SIP name-addr such as `"Alice" <sip:+4930123@gw.example;user=phone>`. */
internal data class SipAddress(
    val displayName: String?,
    /** The URI without parameters or headers, e.g. `sip:+4930123@gw.example`. */
    val uri: String,
    /** The user part, unescaped; null when the URI has none. */
    val user: String?,
) {
    /** The number or user name when there is one, otherwise the whole URI. */
    val shortForm: String get() = user?.takeIf { it.isNotBlank() } ?: uri

    companion object {
        fun parse(nameAddr: String): SipAddress {
            val text = nameAddr.trim()
            val lt = text.indexOf('<')
            val gt = text.lastIndexOf('>')
            val (name, rawUri) =
                if (lt >= 0 && gt > lt) {
                    text
                        .substring(0, lt)
                        .trim()
                        .removeSurrounding("\"")
                        .trim() to text.substring(lt + 1, gt)
                } else {
                    "" to text
                }
            val uri = stripParams(rawUri.trim())
            val user =
                uri
                    .substringAfter(':', "")
                    .takeIf { '@' in it }
                    ?.substringBefore('@')
                    ?.let(::unescape)
            return SipAddress(name.ifBlank { null }?.let(::unescapeQuoted), uri, user)
        }

        /** Drops `;params` after the host part and any `?headers`. */
        private fun stripParams(uri: String): String {
            val noHeaders = uri.substringBefore('?')
            val at = noHeaders.indexOf('@')
            val hostStart = if (at >= 0) at else noHeaders.indexOf(':')
            val semi = noHeaders.indexOf(';', hostStart.coerceAtLeast(0))
            return if (semi >= 0) noHeaders.substring(0, semi) else noHeaders
        }

        private fun unescape(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

        private fun unescapeQuoted(s: String): String = s.replace("\\\"", "\"").replace("\\\\", "\\")
    }
}

/**
 * Turns what the user typed into a SIP URI for [domain]: a full `sip:`/`sips:` URI is kept,
 * `user@host` gets a `sip:` prefix, anything else is treated as a number or user name.
 * Returns null when nothing usable is left.
 */
internal fun dialUri(
    input: String,
    domain: String,
): String? {
    val s = input.trim()
    if (s.isEmpty()) return null
    if (s.startsWith("sip:", ignoreCase = true) || s.startsWith("sips:", ignoreCase = true)) return s
    if (s.startsWith("tel:", ignoreCase = true)) return dialUri(s.substring(4).substringBefore(';'), domain)
    if ('@' in s) return "sip:$s"
    val looksLikeNumber = s.all { it.isDigit() || it in "+*#-. ()/" }
    val user =
        if (looksLikeNumber) {
            s.filter { it.isDigit() || it in "+*#" }
        } else {
            s.filter { !it.isWhitespace() }
        }
    if (user.isEmpty()) return null
    return "sip:${escapeUser(user)}@$domain"
}

/** Escapes characters that are not allowed in a SIP URI user part (RFC 3261 §25.1). */
internal fun escapeUser(user: String): String =
    buildString {
        for (b in user.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-_.!~*'()&=+\$,;?/")) {
                append(ch)
            } else {
                append('%').append("%02X".format(c))
            }
        }
    }
