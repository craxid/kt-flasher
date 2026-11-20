package io.github.craxid.ktflasher

// Porting setia dari bagian image/paket ktflash.py (txtsamu, MIT).
// Semua fungsi di sini murni, belum menyentuh USB sama sekali.

class KtError(msg: String) : RuntimeException(msg)

object KtCrc {
    private val tabel = IntArray(256) { i ->
        var c = i
        repeat(8) {
            c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
        }
        c
    }

    // CRC32 varian KT: reflected, init 0, TANPA xor di akhir. Beda dari CRC32
    // bawaan java.util.zip, jangan tertukar.
    fun hitung(data: ByteArray, dari: Int = 0, panjang: Int = data.size - dari): Int {
        var crc = 0
        for (k in dari until dari + panjang) {
            crc = (crc ushr 8) xor tabel[(crc xor data[k].toInt()) and 0xFF]
        }
        return crc
    }
}

class KtImage(val data: ByteArray) {
    val chipId: ByteArray
    val declaredSize: Int
    val size: Int get() = data.size

    init {
        if (data.size <= BLOK) throw KtError("Image terlalu kecil (${data.size} byte)")
        if (!data.copyOfRange(0, 16).contentEquals(MAGIC)) {
            throw KtError("Magic image salah, bukan firmware KT Helios")
        }
        chipId = data.copyOfRange(0x10, 0x18)
        declaredSize = le32(data, 0x1C)
        if (declaredSize != data.size) {
            throw KtError("Ukuran di header $declaredSize != ukuran berkas ${data.size} (image rusak?)")
        }
    }

    fun chipTeks(): String = String(chipId, Charsets.US_ASCII).trimEnd('\u0000')

    // string ASCII terbaca di 0x1200 byte pertama, sama kayak `image` di CLI
    fun strings(): List<String> {
        val batas = minOf(0x1200, data.size)
        val hasil = ArrayList<String>()
        val sb = StringBuilder()
        for (k in 0 until batas) {
            val b = data[k].toInt() and 0xFF
            if (b in 0x20..0x7E) {
                sb.append(b.toChar())
            } else {
                if (sb.length >= 6) hasil.add(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.length >= 6) hasil.add(sb.toString())
        return hasil
    }

    companion object {
        val MAGIC: ByteArray = "KT_Helios_v1b___".toByteArray(Charsets.US_ASCII)
        const val BLOK = 0x400
        const val HEADER_LEN = 0x10

        fun le32(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)
    }
}

class KtPaket(val data: ByteArray, val addr: Int, val payloadLen: Int, val final: Boolean = false)

fun bangunPaket(payload: ByteArray, addr: Int, top3: Int): ByteArray {
    val n = payload.size
    require(n <= KtImage.BLOK) { "payload > 0x400" }
    val hdr = byteArrayOf(
        0x69,
        (n and 0xFF).toByte(),
        (((n shr 8) and 0x1F) or (top3 shl 5)).toByte(),
        (addr and 0xFF).toByte(),
        ((addr shr 8) and 0xFF).toByte(),
        ((addr shr 16) and 0xFF).toByte()
    )
    val body = hdr + payload
    val crc = KtCrc.hitung(body)
    return body + byteArrayOf(
        (crc and 0xFF).toByte(),
        ((crc shr 8) and 0xFF).toByte(),
        ((crc shr 16) and 0xFF).toByte(),
        ((crc shr 24) and 0xFF).toByte()
    )
}

// Paket data per blok 1 KiB, lalu paket header (16 byte pertama) PALING AKHIR,
// biar flash yang terputus di tengah tetap tidak bisa boot setengah-setengah.
fun rencanaPaket(img: KtImage, base: Int = 0): List<KtPaket> {
    val data = img.data
    val out = ArrayList<KtPaket>()
    var k = 0
    while (k * KtImage.BLOK < data.size) {
        val payload: ByteArray
        val addr: Int
        if (k == 0) {
            payload = data.copyOfRange(KtImage.HEADER_LEN, KtImage.BLOK)
            addr = base + KtImage.HEADER_LEN
        } else {
            val a = k * KtImage.BLOK
            val b = minOf(a + KtImage.BLOK, data.size)
            payload = data.copyOfRange(a, b)
            addr = base + a
        }
        val top3 = if (k % 0x20 == 0) 1 else 0b111
        out.add(KtPaket(bangunPaket(payload, addr, top3), addr, payload.size))
        k++
    }
    out.add(
        KtPaket(
            bangunPaket(data.copyOfRange(0, KtImage.HEADER_LEN), base, 0b111),
            base, KtImage.HEADER_LEN, true
        )
    )
    return out
}
