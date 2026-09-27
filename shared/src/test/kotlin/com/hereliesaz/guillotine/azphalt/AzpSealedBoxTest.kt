package com.hereliesaz.guillotine.azphalt

import org.bouncycastle.math.ec.rfc7748.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AzpSealedBoxTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** NaCl's own `firstkey` test: crypto_box_beforenm(bobpk, alicesk). */
    @Test fun beforeNmMatchesNaCl() {
        val aliceSk = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPk = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        assertArrayEquals(
            hex("1b27556473e985d462cd51197a9a46c76009549eac6474f206c4ee0844f68389"),
            AzpSealedBox.beforeNm(bobPk, aliceSk),
        )
    }

    @Test fun sealThenOpen() {
        val sk = ByteArray(32) { (it * 7 + 3).toByte() }
        val pk = ByteArray(32).also { X25519.scalarMultBase(sk, 0, it, 0) }
        val msg = "provider key 🔑".toByteArray()
        val sealed = AzpSealedBox.seal(msg, pk)
        assertEquals(32 + 16 + msg.size, sealed.size)
        assertArrayEquals(msg, AzpSealedBox.open(sealed, pk, sk))
        sealed[sealed.size - 1] = (sealed.last() + 1).toByte()
        assertNull(AzpSealedBox.open(sealed, pk, sk))
    }
}
