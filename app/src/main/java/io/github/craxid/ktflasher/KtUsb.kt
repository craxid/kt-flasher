package io.github.craxid.ktflasher

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

// Lapisan USB mentah di atas UsbManager Android, meniru FdDevice +
// UsbCdcLink-nya ktflash.py versi Termux (libusb), pindah ke USB Host API.

const val VID_KT = 0x31B2
const val VID_BOOT = 0x8888
const val PID_BOOT = 0xCDC0
const val VID_JA11 = 0x2972
const val PID_JA11 = 0x0102

// report HID 0x54 berisi "12345678": perintah masuk mode bootloader
val UNLOCK_REPORT: ByteArray = byteArrayOf(0x54) + "12345678".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0)

// Android memaparkan tiap alternate setting sebagai UsbInterface terpisah
// dengan id sama; ambil kemunculan pertama (alt 0), kayak perilaku libusb.
fun daftarInterface(dev: UsbDevice): List<UsbInterface> {
    val seen = HashSet<Int>()
    val out = ArrayList<UsbInterface>()
    for (i in 0 until dev.interfaceCount) {
        val intf = dev.getInterface(i)
        if (seen.add(intf.id)) out.add(intf)
    }
    return out
}

fun endpointBulk(intf: UsbInterface): Pair<UsbEndpoint?, UsbEndpoint?> {
    var masuk: UsbEndpoint? = null
    var keluar: UsbEndpoint? = null
    for (i in 0 until intf.endpointCount) {
        val ep = intf.getEndpoint(i)
        if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
        if (ep.direction == UsbConstants.USB_DIR_IN && masuk == null) masuk = ep
        if (ep.direction == UsbConstants.USB_DIR_OUT && keluar == null) keluar = ep
    }
    return masuk to keluar
}

// Kirim report pembuka bootloader ke interface HID dongle (mode aplikasi).
// Balik nomor interface yang dipakai; lempar KtError kalau semua gagal.
fun kirimUnlock(conn: UsbDeviceConnection, dev: UsbDevice, masihTercolok: () -> Boolean): Int {
    var galatTerakhir: String? = null
    for (intf in daftarInterface(dev)) {
        if (intf.interfaceClass != 3) continue
        try {
            if (!conn.claimInterface(intf, true)) {
                galatTerakhir = "klaim interface ${intf.id} ditolak"
                continue
            }
            var epOutInt: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                    ep.direction == UsbConstants.USB_DIR_OUT
                ) {
                    epOutInt = ep
                    break
                }
            }
            val n = if (epOutInt != null) {
                // jalur interrupt OUT, yang disukai hidapi
                conn.bulkTransfer(epOutInt, UNLOCK_REPORT, UNLOCK_REPORT.size, 1000)
            } else {
                // fallback: HID SET_REPORT (output) lewat EP0
                conn.controlTransfer(
                    0x21, 0x09, (2 shl 8) or UNLOCK_REPORT[0].toInt(),
                    intf.id, UNLOCK_REPORT, UNLOCK_REPORT.size, 1000
                )
            }
            if (n >= 0) return intf.id
            // perangkat yang mereset diri ke bootloader bikin transfer gagal;
            // kalau memang sudah hilang dari daftar, itu tandanya berhasil
            if (!masihTercolok()) return intf.id
            galatTerakhir = "transfer ke interface ${intf.id} balik $n"
        } catch (e: Exception) {
            if (!masihTercolok()) return intf.id
            galatTerakhir = e.message
        }
    }
    throw KtError("Tidak bisa mengirim report pembuka bootloader lewat interface HID mana pun (${galatTerakhir ?: "tidak ada interface HID"})")
}

// Tautan serial semu ke bootloader lewat endpoint bulk CDC-ACM.
class TautanCdc(
    private val conn: UsbDeviceConnection,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val diklaim: List<UsbInterface>
) : KtTautan {

    override fun kirim(data: ByteArray) {
        val n = conn.bulkTransfer(epOut, data, data.size, 5000)
        if (n < 0) throw KtError("bulk OUT gagal ($n)")
    }

    private fun bacaMentah(timeoutMs: Int): ByteArray {
        val buf = ByteArray(512)
        val n = conn.bulkTransfer(epIn, buf, buf.size, timeoutMs)
        return if (n > 0) buf.copyOf(n) else ByteArray(0)
    }

    override fun bacaSebagian(): ByteArray = bacaMentah(20)

    override fun kuras() {
        repeat(50) {
            if (bacaMentah(10).isEmpty()) return
        }
    }

    override fun tutup() {
        for (intf in diklaim) {
            try {
                conn.releaseInterface(intf)
            } catch (e: Exception) {
                // abaikan, lagi bersih-bersih
            }
        }
        try {
            conn.close()
        } catch (e: Exception) {
            // abaikan
        }
    }
}

// Buka tautan CDC ke perangkat bootloader: cari interface comm (kelas 2) dan
// data (kelas 10 diutamakan), klaim, lalu setel line coding 115200 8N1 + DTR/RTS.
fun bukaTautanCdc(conn: UsbDeviceConnection, dev: UsbDevice): TautanCdc {
    var comm: UsbInterface? = null
    var dataIntf: UsbInterface? = null
    var epIn: UsbEndpoint? = null
    var epOut: UsbEndpoint? = null
    var cadangan: Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? = null

    for (intf in daftarInterface(dev)) {
        if (intf.interfaceClass == 0x02 && comm == null) comm = intf
        val (masuk, keluar) = endpointBulk(intf)
        if (masuk != null && keluar != null) {
            if (intf.interfaceClass == 0x0A && dataIntf == null) {
                dataIntf = intf
                epIn = masuk
                epOut = keluar
            } else if (cadangan == null) {
                cadangan = Triple(intf, masuk, keluar)
            }
        }
    }
    if (dataIntf == null && cadangan != null) {
        dataIntf = cadangan.first
        epIn = cadangan.second
        epOut = cadangan.third
    }
    val dIntf = dataIntf ?: throw KtError("Endpoint bulk IN/OUT tidak ditemukan di perangkat bootloader")
    val dIn = epIn!!
    val dOut = epOut!!

    val diklaim = ArrayList<UsbInterface>()
    val cIntf = comm
    var commOk = false
    if (cIntf != null) {
        commOk = try {
            conn.claimInterface(cIntf, true)
        } catch (e: Exception) {
            false
        }
        if (commOk) diklaim.add(cIntf)
    }
    if (!conn.claimInterface(dIntf, true)) throw KtError("Klaim interface data CDC gagal")
    diklaim.add(dIntf)

    if (cIntf != null && commOk) {
        try {
            // SET_LINE_CODING 115200 8N1, lalu SET_CONTROL_LINE_STATE DTR+RTS,
            // sama persis kayak jalur pyserial di ktflash.py
            val lineCoding = byteArrayOf(
                0x00, 0xC2.toByte(), 0x01, 0x00, // 115200 little-endian
                0, 0, 8
            )
            conn.controlTransfer(0x21, 0x20, 0, cIntf.id, lineCoding, lineCoding.size, 1000)
            conn.controlTransfer(0x21, 0x22, 3, cIntf.id, null, 0, 1000)
        } catch (e: Exception) {
            // tidak fatal, sebagian bootloader jalan tanpa ini
        }
    }
    return TautanCdc(conn, dIn, dOut, diklaim)
}
