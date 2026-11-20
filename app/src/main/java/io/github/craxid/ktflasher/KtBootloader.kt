package io.github.craxid.ktflasher

import java.io.ByteArrayOutputStream

// Sesi bootloader KT02H20, porting dari kelas Bootloader di ktflash.py.
// Polanya sama persis: kuras buffer, kirim perintah, tunggu byte ack.

interface KtTautan {
    fun kirim(data: ByteArray)
    fun bacaSebagian(): ByteArray
    fun kuras()
    fun tutup()
}

class KtBootloader(
    private val tautan: KtTautan,
    private val log: (String) -> Unit
) {
    companion object {
        val CMD_KTM = byteArrayOf(0x1E, 0x4B, 0x54, 0x4D)
        val CMD_CHP = byteArrayOf(0xD2.toByte(), 0x43, 0x48, 0x50)
        val CMD_ERASE = byteArrayOf(0x2D, 0x29, 0x00, 0x10, 0x0E, 0x15, 0x00, 0x60, 0x00, 0xBC.toByte())
        val CMD_PWO = byteArrayOf(0x3C, 0x50, 0x57, 0x4F)
        val CMD_KSTA = byteArrayOf(0x4B, 0x53, 0x54, 0x41)
        val CMD_STP = byteArrayOf(0x96.toByte(), 0x53, 0x54, 0x50)
        val CMD_RESET = byteArrayOf(0x5A, 0x52, 0x53, 0x54)
        const val ACK_CMD = 0x78
        const val ACK_BLOCK = 0xA5
    }

    private fun tungguByte(b: Int, timeoutMs: Long): ByteArray {
        val akhir = System.currentTimeMillis() + timeoutMs
        val buf = ByteArrayOutputStream()
        while (System.currentTimeMillis() < akhir) {
            val potong = tautan.bacaSebagian()
            if (potong.isNotEmpty()) {
                buf.write(potong)
                if (buf.toByteArray().any { it.toInt() and 0xFF == b }) return buf.toByteArray()
            }
        }
        throw KtError("Tidak ada ack 0x${b.toString(16)} dalam ${timeoutMs / 1000} dtk (dapat ${buf.size()} byte)")
    }

    private fun tungguPanjang(n: Int, timeoutMs: Long): ByteArray {
        val akhir = System.currentTimeMillis() + timeoutMs
        val buf = ByteArrayOutputStream()
        while (System.currentTimeMillis() < akhir) {
            val potong = tautan.bacaSebagian()
            if (potong.isNotEmpty()) {
                buf.write(potong)
                if (buf.size() >= n) return buf.toByteArray()
            }
        }
        throw KtError("Mengharap >= $n byte dalam ${timeoutMs / 1000} dtk (dapat ${buf.size()} byte)")
    }

    private fun perintah(data: ByteArray, ack: Int, timeoutMs: Long) {
        tautan.kuras()
        tautan.kirim(data)
        tungguByte(ack, timeoutMs)
    }

    // KTM dikirim berulang sampai ack pertama; bootloader cuma menerima KTM
    // SATU KALI per boot, jadi kalau gagal total ya cabut-colok dulu.
    fun handshake(timeoutMs: Long = 10_000) {
        val akhir = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < akhir) {
            tautan.kirim(CMD_KTM)
            val t = System.currentTimeMillis() + 15
            val buf = ByteArrayOutputStream()
            while (System.currentTimeMillis() < t) {
                val potong = tautan.bacaSebagian()
                if (potong.isNotEmpty()) buf.write(potong)
            }
            if (buf.toByteArray().any { it.toInt() and 0xFF == ACK_CMD }) return
        }
        throw KtError(
            "Tidak ada ack handshake (0x78). Bootloader hanya menerima KTM sekali per boot: " +
                "cabut dan colok lagi dongle-nya, lalu ulangi."
        )
    }

    fun bacaChipId(): ByteArray {
        tautan.kuras()
        tautan.kirim(CMD_CHP)
        val balas = tungguPanjang(13, 3_000)
        return balas.copyOfRange(4, 12)
    }

    fun flash(rencana: List<KtPaket>, progres: (Int, Int) -> Unit) {
        perintah(CMD_ERASE, ACK_CMD, 20_000)   // <- titik tidak bisa kembali
        perintah(CMD_PWO, ACK_CMD, 5_000)
        perintah(CMD_KSTA, ACK_CMD, 20_000)
        rencana.forEachIndexed { i, pkt ->
            try {
                perintah(pkt.data, ACK_BLOCK, 5_000)
            } catch (e: Exception) {
                throw KtError(
                    "Gagal tulis di paket ${i + 1}/${rencana.size} " +
                        "(addr 0x${pkt.addr.toString(16).padStart(6, '0')}): ${e.message}"
                )
            }
            progres(i + 1, rencana.size)
        }
        perintah(CMD_STP, ACK_CMD, 5_000)
        try {
            perintah(CMD_RESET, ACK_CMD, 3_000)
        } catch (e: Exception) {
            log("Catatan: tidak ada ack untuk RESET (perangkat mungkin sudah reboot sendiri)")
        }
    }
}
