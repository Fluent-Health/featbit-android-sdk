package co.featbit.client.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [ConnectionToken.encode] by decoding its output with a faithful port of FeatBit's
 * server-side `Domain.Shared.Token` / `TokenNumber` decoder. If the round-trip recovers the
 * timestamp and secret and yields a valid 44-char secret, the server will accept the token.
 */
class ConnectionTokenTest {

    // 43-char secret, matching the length of a real FeatBit client key (32 bytes base64, 1 pad).
    private val secret = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"

    @Test
    fun `secret sample is 43 chars`() {
        assertEquals(43, secret.length)
    }

    @Test
    fun `encoded token round-trips through the server decoder`() {
        for (ts in listOf(1L, 999L, 1_000_000_000_000L, 1_781_000_000_000L, System.currentTimeMillis())) {
            val decoded = decode(ConnectionToken.encode(secret, ts))
            assertTrue("token must be valid for ts=$ts", decoded.valid)
            assertEquals("timestamp for ts=$ts", ts, decoded.timestamp)
            // Server pads the secret back to 44 chars; the original (unpadded) must match.
            assertEquals("secret for ts=$ts", secret, decoded.secret.trimEnd('='))
            assertEquals("server requires 44-char secret", 44, decoded.secret.length)
        }
    }

    // --- faithful port of the server's Token decoder ---------------------------------------

    private data class Decoded(val valid: Boolean, val timestamp: Long, val secret: String)

    private val charToDigit = mapOf(
        'Q' to '0', 'B' to '1', 'W' to '2', 'S' to '3', 'P' to '4',
        'H' to '5', 'D' to '6', 'X' to '7', 'Z' to '8', 'U' to '9',
    )

    private fun decodeNumber(chars: String): Long =
        chars.map { charToDigit.getValue(it) }.joinToString("").toLong()

    private fun decode(token: String): Decoded {
        require(token.length >= 5)
        val header = token.substring(0, 5)
        val position = decodeNumber(header.substring(0, 3)).toInt()
        val contentLength = decodeNumber(header.substring(3, 5)).toInt()
        val payload = token.substring(5)
        require(payload.length >= position + contentLength)

        val timestamp = decodeNumber(payload.substring(position, position + contentLength))
        var secret = payload.substring(0, position) + payload.substring(position + contentLength)
        val secretLen = payload.length - contentLength
        if (secretLen % 4 != 0) secret += "=".repeat(4 - secretLen % 4)

        return Decoded(valid = secret.length == 44, timestamp = timestamp, secret = secret)
    }
}
