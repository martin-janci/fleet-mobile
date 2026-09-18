package dev.claudefleet.mobile.data

/**
 * A scanned or typed pairing code, with the hub it names when it came from a QR.
 *
 * [base] is null when the input was a bare code — manual entry, where the
 * operator types the hub's address into its own field.
 */
data class PairTarget(val base: String?, val code: String) {

    companion object {
        /**
         * Crockford base32, exactly as the hub mints it: the digits and the
         * letters, less `I`, `L`, `O` and `U`.
         */
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        /** The hub draws 8 characters: 8 × 5 = 40 bits. */
        private const val CODE_LENGTH = 8

        private const val PAIR_PATH = "/pair"

        /**
         * Read a scan or a typed entry.
         *
         * Two accepted shapes, and nothing else:
         *  - a full pair URL, `<base>/pair#<code>` — what `pair_url` builds and
         *    the QR encodes. The code rides in the *fragment*, which no browser
         *    puts on the wire and no access log ever sees, so it is the only
         *    place worth reading it from;
         *  - a bare code, for someone reading it off the operator's terminal.
         *
         * Null for anything else. Nothing here is thrown, so a camera pointed at
         * an arbitrary QR costs a null rather than an exception per frame.
         */
        fun parse(input: String): PairTarget? {
            val text = input.trim()
            if (text.isEmpty()) return null

            val hash = text.indexOf('#')
            if (hash < 0) return normalizeCode(text)?.let { PairTarget(null, it) }

            val code = normalizeCode(text.substring(hash + 1)) ?: return null
            val base = pairBase(text.substring(0, hash)) ?: return null
            return PairTarget(base, code)
        }

        /**
         * [parse], but throwing [NotAPairingCode] for the screens that owe the
         * person an explanation.
         *
         * The message never repeats the input: a pairing code is a credential
         * for the minutes it lives, and a refusal is exactly the sort of string
         * that ends up in a log or a screenshot.
         */
        fun require(input: String): PairTarget = parse(input)
            ?: throw NotAPairingCode(
                "that is not a claude-fleet pairing code. Scan the QR that " +
                    "`fleet-hub pair` shows, or type the 8-character code beneath it.",
            )

        /**
         * Fold a typed code to what the hub actually minted, or null.
         *
         * Spaces and hyphens are separators Crockford allows for readability, so
         * they come out. `I` and `L` become `1` and `O` becomes `0` — the hub
         * never mints those letters, so folding them can recover a misread
         * character without any chance of turning one valid code into another.
         * `U` is excluded too but has no digit to fold to, so it is a refusal.
         */
        fun normalizeCode(raw: String): String? {
            val cleaned = raw.trim()
                .uppercase()
                .filter { it != '-' && it != ' ' }
                .map {
                    when (it) {
                        'I', 'L' -> '1'
                        'O' -> '0'
                        else -> it
                    }
                }
            if (cleaned.size != CODE_LENGTH) return null
            if (cleaned.any { it !in ALPHABET }) return null
            return cleaned.joinToString("")
        }

        /** `https://host[:port][/prefix]/pair` → the base, or null. */
        private fun pairBase(url: String): String? {
            val trimmed = url.trim()
            val scheme = trimmed.substringBefore("://", "").lowercase()
            if (scheme != "http" && scheme != "https") return null

            // A trailing slash on `/pair/` is the same route.
            val withoutSlash = trimmed.trimEnd('/')
            if (!withoutSlash.lowercase().endsWith(PAIR_PATH)) return null

            val base = withoutSlash.dropLast(PAIR_PATH.length).trimEnd('/')
            // Everything after `://` up to the first `/` is the authority; a URL
            // with none ("https:///pair") names no hub.
            val authority = base.substringAfter("://", "").substringBefore('/')
            if (authority.isEmpty()) return null
            // Userinfo, refused for parity with the hub's own `HubBase::public`
            // ("credentials (user@) are not allowed"), and for one more reason
            // here: `Credentials.hub` is the one field `Credentials.toString()`
            // prints unredacted, so a crafted QR would both route the app
            // through an attacker's host and put `user:pw@` in every log line
            // that prints the auth state.
            if ('@' in authority) return null
            return base
        }
    }
}

/**
 * The scan or the typed entry was not a claude-fleet pairing code.
 *
 * Its message is written for a person and carries no part of the input.
 */
class NotAPairingCode(message: String) : Exception(message)
