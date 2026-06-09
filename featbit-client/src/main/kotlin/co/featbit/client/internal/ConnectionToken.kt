package co.featbit.client.internal

/**
 * Builds the short-lived connection token FeatBit's streaming endpoint expects on the
 * `?token=` query parameter. This is the inverse of the server's `Domain.Shared.Token`
 * decoder: the timestamp's decimal digits are obfuscated through a fixed character map and
 * spliced into the secret at a chosen position; a 5-char header records that position (3
 * chars) and the timestamp length (2 chars).
 *
 * The server rejects tokens older than ~30s, so a fresh token must be generated for every
 * (re)connect.
 */
internal object ConnectionToken {

    // Digit -> obfuscation char, matching the server's TokenNumber.CharacterMap.
    private val DIGIT_TO_CHAR = mapOf(
        '0' to 'Q', '1' to 'B', '2' to 'W', '3' to 'S', '4' to 'P',
        '5' to 'H', '6' to 'D', '7' to 'X', '8' to 'Z', '9' to 'U',
    )

    fun encode(secret: String, timestampMs: Long = System.currentTimeMillis()): String {
        val s = secret.trimEnd('=')
        val timestampDigits = timestampMs.toString()
        val contentLength = timestampDigits.length
        val position = if (s.isEmpty()) 0 else (timestampMs % s.length).toInt()

        val header = encodeNumber(position, width = 3) + encodeNumber(contentLength, width = 2)
        val encodedTimestamp = timestampDigits.map(DIGIT_TO_CHAR::getValue).joinToString("")
        val payload = s.substring(0, position) + encodedTimestamp + s.substring(position)

        return header + payload
    }

    private fun encodeNumber(value: Int, width: Int): String =
        value.toString().padStart(width, '0').map(DIGIT_TO_CHAR::getValue).joinToString("")
}
