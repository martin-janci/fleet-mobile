package dev.claudefleet.mobile.net

import kotlin.test.Test
import kotlin.test.assertEquals

/** [Sha256] against the FIPS 180-4 / NIST example vectors, fed whole and in awkward pieces. */
class Sha256Test {
    private fun digest(text: String): String = Sha256().apply { update(text.encodeToByteArray()) }.hex()

    @Test
    fun the_nist_vectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", digest(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest("abc"))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    /** One million `a`, fed in 997-byte pieces so blocks straddle every call. */
    @Test
    fun a_million_as_in_pieces_that_straddle_blocks() {
        val hash = Sha256()
        val piece = ByteArray(997) { 'a'.code.toByte() }
        var left = 1_000_000
        while (left > 0) {
            val n = minOf(left, piece.size)
            hash.update(piece, 0, n)
            left -= n
        }
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", hash.hex())
    }

    /** 55, 56 and 64 bytes: the padding's own boundaries. */
    @Test
    fun the_padding_boundaries() {
        assertEquals("9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318", digest("a".repeat(55)))
        assertEquals("b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a", digest("a".repeat(56)))
        assertEquals("ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb", digest("a".repeat(64)))
    }
}
