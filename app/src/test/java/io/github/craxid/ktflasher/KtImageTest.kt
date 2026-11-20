package io.github.craxid.ktflasher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Vektor acuan di test ini dihitung dari ktflash.py asli, jadi kalau porting
// Kotlin-nya meleset sedikit saja dari referensi Python, test-nya langsung merah.
class KtImageTest {

    private fun ByteArray.hex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun crcVektor() {
        assertEquals(0x2dfd2d88, KtCrc.hitung("123456789".toByteArray(Charsets.US_ASCII)))
        assertEquals(0, KtCrc.hitung(ByteArray(0)))
    }

    @Test
    fun bangunPaketVektor() {
        val p = bangunPaket(ByteArray(16) { it.toByte() }, 0x10, 1)
        assertEquals("691020100000000102030405060708090a0b0c0d0e0ff423958c", p.hex())
    }

    private fun cekPaket(
        p: KtPaket, addr: Int, len: Int, hdr: String,
        total: Int, trailer: String, final: Boolean
    ) {
        assertEquals(addr, p.addr)
        assertEquals(len, p.payloadLen)
        assertEquals(hdr, p.data.copyOfRange(0, 6).hex())
        assertEquals(total, p.data.size)
        assertEquals(trailer, p.data.copyOfRange(p.data.size - 4, p.data.size).hex())
        assertEquals(final, p.final)
    }

    @Test
    fun rencanaImageSintetis() {
        // image sintetis deterministik 67312 byte, pola sama dengan yang
        // dipakai menghitung vektor di sisi Python
        val data = ByteArray(67312)
        KtImage.MAGIC.copyInto(data, 0)
        "KT02H20B".toByteArray(Charsets.US_ASCII).copyInto(data, 0x10)
        data[0x1C] = (67312 and 0xFF).toByte()
        data[0x1D] = ((67312 shr 8) and 0xFF).toByte()
        data[0x1E] = ((67312 shr 16) and 0xFF).toByte()
        data[0x1F] = 0
        for (i in 0x20 until data.size) data[i] = (i % 251).toByte()

        val img = KtImage(data)
        assertEquals("KT02H20B", img.chipTeks())
        assertEquals(67312, img.declaredSize)
        assertEquals(0xb714716e.toInt(), KtCrc.hitung(img.data))

        val rencana = rencanaPaket(img)
        assertEquals(67, rencana.size)
        cekPaket(rencana[0], 0x10, 1008, "69f023100000", 1018, "87207a23", false)
        cekPaket(rencana[1], 0x400, 1024, "6900e4000400", 1034, "de1327fe", false)
        cekPaket(rencana[32], 0x8000, 1024, "690024008000", 1034, "bcc7bb5d", false)
        cekPaket(rencana[65], 0x10400, 752, "69f0e2000401", 762, "e7c7cac8", false)
        cekPaket(rencana[66], 0, 16, "6910e0000000", 26, "b665adc7", true)
    }

    @Test
    fun imageRusakDitolak() {
        var ditolak = false
        try {
            KtImage(ByteArray(2048))
        } catch (e: KtError) {
            ditolak = true
        }
        assertTrue(ditolak)
    }
}
