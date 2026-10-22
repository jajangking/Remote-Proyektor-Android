# Remote Proyektor Android

**📲 [Unduh APK rilis di halaman Releases](https://github.com/jajangking/Remote-Proyektor-Android/releases/latest)**

Remote control proyektor Android TV (tes di ADT-3, Android 11) dari HP lewat **WiFi** —
untuk kasus remote fisik hilang. Terdiri dari:

- **APK `tvremote.apk`** (HP): touchpad ala mouse, D-pad 8 arah, keyboard, tombol lengkap.
- **2 daemon kecil** (proyektor, jalan tanpa root):
  - port **9877** — shell: eksekusi perintah (`input keyevent`, `input text`, dll).
  - port **9878** — pointer virtual via `/dev/uhid`: kursor mouse, klik, scroll
    (daftarkan device "TV Remote Virtual Mouse" + "TV Remote Virtual Touchscreen").

## Cara pakai

1. Proyektor & HP satu jaringan WiFi.
2. Buka app → pilih mode:
   - **Service (9877)** — default, butuh daemon (sudah otomatis terpasang via auto-setup).
   - **ADB (5555)** — via adbd TCP.
3. Isi IP proyektor (atau tekan **Scan**) → **Hubungkan**. IP terakhir diingat otomatis.

### Auto-setup dari HP (sekali jalan)

App bisa memasang daemon sendiri ke proyektor bersih yang adb TCP-nya aktif
(`service.adb.tcp.port=5555`): handshake auth → push 3 file → install → kedua port
langsung LISTEN. Tanpa Termux, tanpa ketik manual. Detail & riwayat diagnosanya ada di
[HANDOFF.md](HANDOFF.md).

## Struktur

```
src/                     # kode Java app (AdbClient, AdbKey, MainActivity, DaemonPayload)
vmoused.c / vmoused32    # daemon pointer UHID (relatif + touchscreen absolut)
install_daemon.sh        # skrip pemasangan daemon shell (9877)
install_vmoused.sh       # skrip pemasangan daemon mouse (9878)
build_apk.sh             # pipeline build: aapt2 -> javac -> d8 -> zip -> apksigner
findcursor.py            # alat bantu lacak posisi kursor
portmap.sh               # pemetaan port
HANDOFF.md               # dokumen teknis lengkap: akar bug, jebakan, status
```

## Build

```sh
./build_apk.sh           # hasil: build/out/tvremote.apk
adb install -r build/out/tvremote.apk
```

Butuh `build/android.jar` (API 30) — masukkan ke folder `build/` (ter-ignore git).

## Catatan keamanan

Port 9877/9878 **tanpa autentikasi** — hanya aman di jaringan WiFi tepercaya.
Jangan buka ke publik.
