# KT Flasher

Aplikasi Android untuk flash firmware FiiO JA11 ke dongle USB DAC berbasis chip KTMicro KT02H20, langsung dari HP. Tanpa PC, tanpa root.

by CraXID Project

## Tentang

Dongle generik KT02H20 memakai chip yang sama dengan FiiO JA11, jadi firmware JA11 bisa ditulis ke dongle generik itu dan dongle berubah menjadi JA11 secara fungsi: dikenali aplikasi FiiO Control, dapat EQ bawaan, dan terbaca sebagai JadeAudio JA11 di perangkat lain. KT Flasher melakukan proses flash itu dari Android lewat USB Host API. Firmware Tinhifi untuk chip yang sama juga bisa ditulis ke dongle ini dan sudah terkonfirmasi berhasil.

## Fitur

- **Deteksi Dongle**: membaca identitas dongle yang tercolok, baik mode aplikasi KT (31b2:0111), mode bootloader (8888:cdc0), maupun JA11 hasil flash (2972:0102)
- **Validasi firmware**: berkas .bin diperiksa dulu sebelum apa pun ditulis (magic, chip id, ukuran paket, dan CRC image)
- **Dry run**: menampilkan rencana paket yang akan ditulis tanpa menyentuh dongle
- **Flash aman**: chip perangkat wajib cocok dengan image, ada konfirmasi eksplisit sebelum hapus dan tulis, dan header image ditulis paling akhir
- **Tutorial bawaan**: panduan langkah flash dan cara pemulihan ada di dalam aplikasi
- **Log lengkap**: tiap langkah tercatat dan bisa dibersihkan

## Perangkat yang didukung

- Dongle USB DAC generik KT02H20 (chip KT02H20B)
- JCALLY JM12, hardwarenya sama
- FiiO JA11 asli, untuk probe dan baca status
- Firmware Tinhifi untuk KT02H20, sudah terkonfirmasi bisa ditulis ke dongle di atas

## Syarat

- HP Android 7.0 (API 24) atau lebih baru dengan USB host / OTG
- Dongle USB-C, atau adapter yang sesuai

## Peringatan

- **Flash bersifat satu arah.** Firmware bawaan dongle generik tidak bisa dibaca dan tidak bisa dicadangkan, jadi sesudah flash tidak ada jalan kembali ke firmware asal.
- Pakai hanya firmware yang chip id-nya cocok. Image JA11 V2.2 cocok untuk KT02H20B. Image yang salah bisa membuat dongle tidak berfungsi.
- Bila flash gagal di tengah jalan, dongle tertahan di mode bootloader. Cabut, colok ulang, lalu ulangi flash dari awal. Dongle tidak rusak permanen.

## Cara pakai

1. Colok dongle, buka KT Flasher, tekan **Deteksi Dongle** dan pastikan dongle terbaca.
2. Tekan **Pilih Firmware**, pilih firmware .bin, misalnya JA11 V2.2 atau firmware Tinhifi, lalu periksa hasil validasinya.
3. Bila mau, jalankan **Dry run** untuk melihat rencana paketnya.
4. Tekan **Flash**. Izin USB muncul dua kali (mode aplikasi lalu mode bootloader), itu normal. Setujui keduanya, lalu konfirmasi hapus dan tulis.
5. Sesudah selesai, cabut dan colok ulang dongle. Dongle sekarang terbaca sebagai JadeAudio JA11.

## Build dari source

Proyek Gradle Android biasa: Kotlin, Views tanpa Compose, minSdk 24, compileSdk 35, JDK 17.

Signing rilis membaca `keystore-final.properties` di root proyek (berkas ini sengaja tidak ikut di repo). Isi berkas itu dengan `storeFile`, `storePassword`, `keyAlias`, dan `keyPassword` milikmu, atau build varian debug untuk sekadar mencoba.

## Kredit

KT Flasher adalah port Android dari `ktflash.py` di repo [KT02H20-FLASHER-RE](https://github.com/dragonzords/KT02H20-FLASHER-RE) karya txtsamu / dragonzords, yang dilisensikan MIT. Terima kasih atas kerja reverse engineering protokol KTM-nya.

Firmware JA11 adalah milik FiiO dan **tidak disertakan** di repo ini. Pengguna memilih berkas firmwarenya sendiri.

Aplikasi ini proyek komunitas dan tidak berafiliasi dengan FiiO maupun KTMicro.

## Lisensi

MIT, lihat berkas [LICENSE](LICENSE). Karya asal txtsamu juga berlisensi MIT; pemberitahuan hak cipta lengkapnya ada di dalam aplikasi, menu Tentang & Lisensi.
