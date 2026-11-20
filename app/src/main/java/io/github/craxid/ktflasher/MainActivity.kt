package io.github.craxid.ktflasher

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// KT Flasher: porting ktflash.py (CLI) jadi aplikasi Android satu layar.
// Semua urusan USB jalan di thread pekerja; UI cuma menampilkan log + status.
class MainActivity : Activity() {

    companion object {
        private const val ACTION_IZIN = "io.github.craxid.ktflasher.IZIN_USB"
        private const val REQ_PILIH = 42
    }

    private lateinit var usbManager: UsbManager
    private lateinit var statusTeks: TextView
    private lateinit var infoFirmware: TextView
    private lateinit var logTeks: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var progres: ProgressBar
    private lateinit var tombol: List<Button>

    private val pekerja = Executors.newSingleThreadExecutor()

    @Volatile
    private var image: KtImage? = null

    // jembatan thread pekerja <-> prompt izin USB & peristiwa colok perangkat
    @Volatile
    private var izinLatch: CountDownLatch? = null

    @Volatile
    private var izinOk = false

    @Volatile
    private var attachLatch: CountDownLatch? = null

    private val penerima = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_IZIN -> {
                    izinOk = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    izinLatch?.countDown()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> attachLatch?.countDown()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        usbManager = getSystemService(USB_SERVICE) as UsbManager

        // targetSdk 35 menggambar aplikasi edge-to-edge sampai ke bawah
        // status bar, jadi isi digeser pakai inset biar judul tidak tertimpa
        val akar = findViewById<View>(R.id.akar)
        val alasKiri = akar.paddingLeft
        val alasAtas = akar.paddingTop
        val alasKanan = akar.paddingRight
        val alasBawah = akar.paddingBottom
        akar.setOnApplyWindowInsetsListener { v, insets ->
            val atas: Int
            val bawah: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                atas = bars.top
                bawah = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                atas = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bawah = insets.systemWindowInsetBottom
            }
            v.setPadding(alasKiri, alasAtas + atas, alasKanan, alasBawah + bawah)
            insets
        }

        statusTeks = findViewById(R.id.statusTeks)
        infoFirmware = findViewById(R.id.infoFirmware)
        logTeks = findViewById(R.id.logTeks)
        logScroll = findViewById(R.id.logScroll)
        progres = findViewById(R.id.progres)

        val probeBtn = findViewById<Button>(R.id.probeBtn)
        val pilihBtn = findViewById<Button>(R.id.pilihBtn)
        val dryBtn = findViewById<Button>(R.id.dryBtn)
        val flashBtn = findViewById<Button>(R.id.flashBtn)
        val tentangBtn = findViewById<Button>(R.id.tentangBtn)
        val bersihBtn = findViewById<Button>(R.id.bersihBtn)
        tombol = listOf(probeBtn, pilihBtn, dryBtn, flashBtn, bersihBtn)

        probeBtn.setOnClickListener { probe() }
        pilihBtn.setOnClickListener {
            val niat = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(niat, REQ_PILIH)
        }
        dryBtn.setOnClickListener { dryRun() }
        flashBtn.setOnClickListener {
            val img = image
            if (img == null) {
                log("Pilih berkas firmware dulu sebelum flash.")
            } else {
                setSibuk(true)
                pekerja.execute { sesiFlash(img) }
            }
        }
        tentangBtn.setOnClickListener { tampilTentang() }
        findViewById<Button>(R.id.tutorialBtn).setOnClickListener {
            tampilDialog("Tutorial", teksTutorial)
        }
        findViewById<Button>(R.id.developerBtn).setOnClickListener {
            tampilDialog("Tentang Developer", teksDeveloper)
        }
        bersihBtn.setOnClickListener {
            // kosongkan log, sisakan baris siap kayak pas aplikasi baru dibuka
            logTeks.text = ""
            log("Log dibersihkan. KT Flasher siap.")
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_IZIN)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        registerReceiver(penerima, filter, Context.RECEIVER_NOT_EXPORTED)

        log("KT Flasher siap. Colok dongle KT02H20 lewat OTG, lalu Probe USB.")
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            log("Ada perangkat USB baru tercolok.")
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(penerima)
        } catch (e: Exception) {
            // sudah lepas, abaikan
        }
        pekerja.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------ util UI
    private fun log(s: String) {
        runOnUiThread {
            logTeks.append(s + "\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setStatus(s: String) {
        runOnUiThread { statusTeks.text = "Status: $s" }
    }

    private fun setSibuk(b: Boolean) {
        runOnUiThread {
            // selama flash layar jangan sampai mati, koneksi USB rawan putus
            if (b) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            tombol.forEach { it.isEnabled = !b }
            if (b) {
                progres.visibility = View.VISIBLE
                progres.progress = 0
            } else {
                progres.visibility = View.GONE
            }
        }
    }

    private fun setProgres(i: Int, n: Int) {
        runOnUiThread { progres.progress = if (n > 0) 100 * i / n else 0 }
    }

    // ------------------------------------------------------------ probe
    private fun probe() {
        val daftar = usbManager.deviceList.values.toList()
        if (daftar.isEmpty()) {
            log("Tidak ada perangkat USB yang terhubung. Dongle-nya sudah colok OTG?")
            return
        }
        log("Perangkat USB yang terhubung:")
        for (dev in daftar) {
            val tag = when {
                dev.vendorId == VID_BOOT && dev.productId == PID_BOOT -> " <- bootloader"
                dev.vendorId == VID_KT -> " <- dongle KT (mode aplikasi)"
                dev.vendorId == VID_JA11 && dev.productId == PID_JA11 -> " <- JadeAudio JA11"
                else -> ""
            }
            val nama = try {
                dev.productName ?: dev.deviceName
            } catch (e: Exception) {
                dev.deviceName
            }
            log("  %04x:%04x %s%s".format(dev.vendorId, dev.productId, nama, tag))
        }
    }

    // ------------------------------------------------------------ pilih firmware
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PILIH || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        pekerja.execute {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw KtError("Berkas tidak bisa dibaca")
                val img = KtImage(bytes)
                image = img
                val nama = namaTampil(uri) ?: "firmware.bin"
                val rencana = rencanaPaket(img)
                runOnUiThread {
                    infoFirmware.text =
                        "Firmware: $nama (${img.size} byte)\n" +
                            "Chip: ${img.chipTeks()} - paket: ${rencana.size - 1} data + 1 final = ${rencana.size}\n" +
                            "CRC image: 0x${KtCrc.hitung(img.data).toUInt().toString(16).padStart(8, '0')}"
                }
                log("Image valid: $nama, chip ${img.chipTeks()}, ${img.size} byte, ${rencana.size} paket.")
                for (s in img.strings()) log("  string: $s")
            } catch (e: Exception) {
                image = null
                runOnUiThread { infoFirmware.text = "Firmware: belum dipilih" }
                log("Image ditolak: ${e.message}")
            }
        }
    }

    private fun namaTampil(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ dry run
    private fun dryRun() {
        val img = image
        if (img == null) {
            log("Pilih berkas firmware dulu sebelum dry run.")
            return
        }
        val rencana = rencanaPaket(img)
        log("DRY RUN - tidak ada yang dikirim ke dongle. Paket pertama dan terakhir:")
        for (p in rencana.take(3) + rencana.last()) {
            val hdr = p.data.copyOfRange(0, 6).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
            log(
                "  addr 0x${p.addr.toString(16).padStart(6, '0')} len %4d hdr %s%s".format(
                    p.payloadLen, hdr, if (p.final) "  (final)" else ""
                )
            )
        }
        log("Total ${rencana.size} paket. Flash beneran pakai tombol Flash.")
    }

    // ------------------------------------------------------------ sesi flash
    private fun mintaIzin(dev: UsbDevice): Boolean {
        if (usbManager.hasPermission(dev)) return true
        val latch = CountDownLatch(1)
        izinLatch = latch
        izinOk = false
        val pi = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_IZIN).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE
        )
        usbManager.requestPermission(dev, pi)
        val selesai = latch.await(90, TimeUnit.SECONDS)
        return selesai && izinOk && usbManager.hasPermission(dev)
    }

    private fun cariPerangkat(pred: (UsbDevice) -> Boolean): UsbDevice? =
        usbManager.deviceList.values.firstOrNull(pred)

    private fun tungguBootloader(timeoutMs: Long = 60_000): UsbDevice? {
        cariPerangkat { it.vendorId == VID_BOOT && it.productId == PID_BOOT }?.let { return it }
        val latch = CountDownLatch(1)
        attachLatch = latch
        val akhir = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < akhir) {
            latch.await(500, TimeUnit.MILLISECONDS)
            cariPerangkat { it.vendorId == VID_BOOT && it.productId == PID_BOOT }?.let {
                attachLatch = null
                return it
            }
        }
        attachLatch = null
        return null
    }

    private fun konfirmasiTulis(chipTeks: String): Boolean {
        val latch = CountDownLatch(1)
        val hasil = AtomicBoolean(false)
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Tulis firmware?")
                .setMessage(
                    "Chip cocok ($chipTeks). Langkah berikutnya MENGHAPUS firmware yang " +
                        "sekarang terpasang, lalu menulis image terpilih.\n\n" +
                        "Firmware asli TIDAK BISA dibackup lewat perangkat lunak, karena " +
                        "bootloader tidak punya perintah baca. Kalau tulisan terputus di " +
                        "tengah, dongle tetap di mode bootloader dan bisa ditulis ulang, " +
                        "tidak brick."
                )
                .setPositiveButton("Hapus & Tulis") { _, _ ->
                    hasil.set(true)
                    latch.countDown()
                }
                .setNegativeButton("Batal") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        latch.await(120, TimeUnit.SECONDS)
        return hasil.get()
    }

    private fun sesiFlash(img: KtImage) {
        var tautan: KtTautan? = null
        var sudahHapus = false
        try {
            setStatus("menyiapkan sesi flash")
            // 1) bootloader sudah ada? kalau belum, unlock dongle KT dulu
            var boot = cariPerangkat { it.vendorId == VID_BOOT && it.productId == PID_BOOT }
            if (boot == null) {
                val kt = cariPerangkat { it.vendorId == VID_KT }
                    ?: throw KtError(
                        "Dongle tidak ditemukan (VID 0x31b2 atau bootloader 8888:cdc0). " +
                            "Cek colokan OTG-nya."
                    )
                log("Dongle KT ketemu (%04x:%04x), meminta izin USB...".format(kt.vendorId, kt.productId))
                if (!mintaIzin(kt)) throw KtError("Izin USB untuk dongle ditolak")
                val conn = usbManager.openDevice(kt)
                    ?: throw KtError("Gagal membuka dongle")
                try {
                    val ifc = kirimUnlock(conn, kt) {
                        usbManager.deviceList.containsKey(kt.deviceName)
                    }
                    log("Report pembuka bootloader terkirim (interface HID $ifc).")
                } finally {
                    try {
                        conn.close()
                    } catch (e: Exception) {
                        // abaikan
                    }
                }
                log("Menunggu bootloader muncul sebagai 8888:cdc0... setujui prompt izin USB berikutnya.")
                boot = tungguBootloader()
                    ?: throw KtError("Bootloader (8888:cdc0) tidak muncul / tidak diizinkan dalam 60 dtk")
            } else {
                log("Dongle sudah dalam mode bootloader (8888:cdc0).")
            }

            // 2) buka tautan CDC ke bootloader
            if (!mintaIzin(boot)) throw KtError("Izin USB untuk bootloader ditolak")
            val conn = usbManager.openDevice(boot)
                ?: throw KtError("Gagal membuka perangkat bootloader")
            val link = bukaTautanCdc(conn, boot)
            tautan = link
            val bl = KtBootloader(link) { log(it) }

            // 3) handshake + cek chip, semua SEBELUM ada yang dihapus
            bl.handshake()
            log("Handshake bootloader OK.")
            val chip = bl.bacaChipId()
            val chipTeks = String(chip, Charsets.US_ASCII).trimEnd('\u0000')
            log("Chip id perangkat: $chipTeks - chip id image: ${img.chipTeks()}")
            if (!chip.contentEquals(img.chipId)) {
                throw KtError("Chip TIDAK cocok dengan image - dibatalkan sebelum ada yang dihapus")
            }

            // 4) konfirmasi terakhir, pasangan --yes + --no-backup-ok di CLI
            if (!konfirmasiTulis(chipTeks)) {
                log(
                    "Dibatalkan sebelum hapus. Dongle masih di mode bootloader; " +
                        "cabut-colok untuk balik ke mode normal."
                )
                setStatus("dibatalkan")
                return
            }

            // 5) tulis beneran
            sudahHapus = true
            setStatus("menulis firmware")
            val rencana = rencanaPaket(img)
            log("Menulis ${rencana.size} paket...")
            val t0 = System.currentTimeMillis()
            bl.flash(rencana) { i, n ->
                if (i % 8 == 0 || i == n) log("  menulis ${100 * i / n}%  ($i/$n)")
                setProgres(i, n)
            }
            val dtk = (System.currentTimeMillis() - t0) / 1000.0
            log("Tulis selesai dalam ${"%.1f".format(dtk)} dtk.")
            log("Cabut dan colok lagi dongle-nya untuk boot firmware baru.")
            setStatus("selesai, firmware tertulis")
        } catch (e: Exception) {
            log("Error: ${e.message}")
            if (sudahHapus) {
                log("Dongle TIDAK brick: boot ROM-nya utuh. Cabut-colok lalu ulangi dengan image yang valid.")
            }
            setStatus("gagal")
        } finally {
            try {
                tautan?.tutup()
            } catch (e: Exception) {
                // abaikan
            }
            setSibuk(false)
        }
    }

    // ------------------------------------------------------------ tentang
    private fun tampilTentang() {
        tampilDialog("Tentang KT Flasher", teksTentang)
    }

    private fun tampilDialog(judul: String, isi: String) {
        val teks = TextView(this).apply {
            text = isi
            setPadding(48, 32, 48, 16)
            textSize = 13f
        }
        val gulir = ScrollView(this).apply { addView(teks) }
        AlertDialog.Builder(this)
            .setTitle(judul)
            .setView(gulir)
            .setPositiveButton("Tutup", null)
            .show()
    }

    private val teksTutorial: String = """
            Cara pakai

            1. Siapkan dongle KT02H20 (atau JCALLY JM12) dan adaptor
               OTG, lalu colok dongle ke HP.
            2. Tekan Probe USB. Dongle yang sehat terbaca 31b2:xxxx
               (mode aplikasi) atau 2972:0102 (sudah memakai firmware
               JA11).
            3. Tekan Pilih Firmware, lalu pilih berkas .bin firmware,
               misalnya JadeAudio JA11_V2.2.bin. Aplikasi memeriksa
               berkasnya dulu; berkas yang bukan firmware KT Helios
               akan ditolak.
            4. Tekan Dry Run untuk melihat rencana paket yang akan
               dikirim, tanpa mengirim apa pun ke dongle.
            5. Tekan Flash. Izin USB akan muncul, setujui. Dongle
               masuk mode bootloader dan izin USB muncul sekali lagi
               untuk perangkat 8888:cdc0, setujui juga. Izin dua kali
               ini normal.
            6. Aplikasi membaca ID chip dan mencocokkannya dengan
               firmware. Kalau tidak cocok, proses berhenti sebelum
               ada yang dihapus.
            7. Setelah dialog konfirmasi terakhir kamu setujui,
               penghapusan dimulai dan tidak bisa dibatalkan. Jangan
               cabut dongle dan jangan tutup aplikasi. Layar tetap
               menyala selama flash berlangsung.
            8. Tunggu sampai selesai. Dongle reset sendiri dan
               sesudahnya terbaca sebagai JA11 (2972:0102).

            Kalau gagal di tengah jalan, dongle biasanya hanya
            tertahan di mode bootloader, bukan rusak. Cabut, colok
            lagi, lalu ulangi flash dari awal.

            Penting: firmware bawaan dongle tidak bisa dibaca atau
            dicadangkan, jadi flash bersifat satu arah. Pastikan
            berkas firmware-nya benar sebelum mulai.
        """.trimIndent()

    private val teksDeveloper: String = """
            CraXID Project

            Pengembang aplikasi Android dan alat kecil untuk
            kebutuhan sendiri yang dibagikan terbuka.

            Situs: https://hxa.my.id
            Email: dev@hxa.my.id
            GitHub: https://github.com/craxid

            KT Flasher adalah porting Android dari ktflash
            (KT02H20-FLASHER-RE) karya txtsamu dan dragonzords.
            Kredit dan lisensi MIT selengkapnya ada di tombol
            Tentang & Lisensi.
        """.trimIndent()

    private val teksTentang: String = """
            KT Flasher 1.1.0
            by CraXID Project - https://hxa.my.id

            Alat flash firmware untuk dongle DAC USB KTMicro KT02H20
            (termasuk JCALLY JM12 dan FiiO JA11), porting dari alat
            baris perintah ktflash.py. Berjalan langsung dari HP,
            tanpa PC, tanpa root, tanpa Termux.

            Berdasarkan KT02H20-FLASHER-RE:
            https://github.com/dragonzords/KT02H20-FLASHER-RE

            Lisensi MIT

            Copyright (c) 2026 txtsamu

            Permission is hereby granted, free of charge, to any person
            obtaining a copy of this software and associated
            documentation files (the "Software"), to deal in the
            Software without restriction, including without limitation
            the rights to use, copy, modify, merge, publish,
            distribute, sublicense, and/or sell copies of the Software,
            and to permit persons to whom the Software is furnished to
            do so, subject to the following conditions:

            The above copyright notice and this permission notice shall
            be included in all copies or substantial portions of the
            Software.

            THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
            KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
            WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
            PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS
            OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR
            OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
            OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
            SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
        """.trimIndent()
}
