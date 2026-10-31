# HANDOFF — Proyektor ADT-3 Virtual Pointer (UHID)

> **Untuk AI/agent berikutnya yang punya tool `shell`.**
> Semua yang di bawah ini adalah hasil kerja nyata di device, bukan teori.
> Baca sampai habis sebelum menyentuh apa pun.

---

## 0. Ringkasan satu paragraf

User kehilangan remote fisik proyektor ADT-3. App Android
(`~/tvremote/build/out/tvremote.apk`, package `com.projector.tvremote`) mengontrol
proyektor lewat WiFi tanpa ADB saat runtime, pakai **dua daemon** di proyektor:
- **port 9877** = shell daemon (uid shell) via `toybox nc` → `input keyevent`, dll.
- **port 9878** = daemon touchscreen virtual (`vmoused32`) via `/dev/uhid`.

**SEMUA SUDAH BERHASIL (2026-10-20 malam):**
- D-Pad & tombol: 100% jalan (sejak awal).
- **Touchpad absolut: SUDAH JALAN & PRESISI 1:1.** Terbukti: tap (640,150) di
  Settings membuka `ProjectorActivity`; double-tap (640,450) membuka File Manager;
  crosshair overlay `pointer_location` muncul tepat di koordinat yang dikirim.
- APK versi touchpad-absolut sudah di-build & terinstall di HP user.

**Catatan pemakaian:** di **launcher** (`twdlaunchernet`) tap **2x** diperlukan
(tap pertama memunculkan kursor). Di **aplikasi biasa** (Settings dll) tap 1x
sudah cukup. Drag jalan via `D x y` (tekan+pindah).

**Auto-setup penuh TERBUKTI E2E (2026-10-22):** app yang diluncurkan dengan
intent `--es host <ip> --es mode adb --ez autoconnect true` ke proyektor
**kosong total** (tak ada file daemon) bisa sendiri: handshake → push 3 file
(md5 identik) → jalankan 2 installer → port 9877/9878 LISTEN → kedua device
input terdaftar → touchpad tersambung. Tanpa Termux/AI di sisi proyektor —
syaratnya hanya **server adb (`adb start-server`) jalan di Termux HP** dan
proyektor sudah `service.adb.tcp.port=5555` (sekali set via ADB, persist
selama tidak factory reset).

---

## 1. Environment

| Item | Nilai |
|---|---|
| Proyektor | ADT-3 "projector", Android 11 (API 30), 1280x720 @240dpi |
| CPU proyektor | `armeabi-v7a` (32-bit only), Allwinner `sun50iw12p1`, kernel 5.4.99 armv7l |
| IP proyektor | `10.193.162.28` (ADB `:5555`) — **DHCP/renumber bisa mengubah kapan saja** (dulu `10.80.108.28`; jaringan STARLINK pindah subnet 2026-10-22) |
| ADB TCP | `service.adb.tcp.port=5555` (terbuka; persistensi reboot belum dikonfirmasi) |
| Root proyektor | **TIDAK ADA** (`adbd cannot run as root in production builds`) |
| SELinux proyektor | **Permissive** |
| Shell uid | `uid=2000(shell)`, grup termasuk `1004(input)` dan `uhid` |
| HP (Termux) | TECNO LJ8k, `aarch64`, IP `10.193.162.48` (dulu `10.80.108.48`) |
| ADB HP | wireless debugging `10.193.162.48:37711` — **port & IP random tiap sesi; kini minta TLS (STLS) — pakai adb CLI, jangan protokol mentah** |
| WiFi | SSID `STARLINK` |

---

## 2. Fakta kunci yang sudah DIBUKTIKAN (jangan diulang tesnya)

### 2.1 `/dev/uhid` bisa dipakai
- `crw-rw---- uhid uhid 10,239 /dev/uhid`, shell ada di grup `uhid` ✅
- `/dev/uinput` juga bisa dibuka shell (grup sama), tapi tidak terpakai.
- `CONFIG_UHID=y`, SELinux Permissive ✅
- Struct `uhid_event`: ukuran 4380 di header Termux saat ini; pakai
  `#include <linux/uhid.h>` resmi. `UHID_CREATE2`=11, `UHID_INPUT2`=12.
- `write()` gagal `EOPNOTSUPP (95)` = struct salah ukuran.

### 2.2 ⚠️ Mouse ABSOLUT MURNI (tanpa REL) = GAGAL TOTAL — JANGAN DICOBA LAGI
- Descriptor mouse (Usage Mouse) dengan `ABS_X/ABS_Y` saja, **tanpa** `REL_X/REL_Y`:
  - Kernel mendaftarkan device ✅ (`hid-generic ... USB HID Mouse`) ✅
  - `getevent -p` menunjukkan ABS 0..32767 dengan benar ✅
  - TAPI **InputReader TIDAK PERNAH mengadopsinya** — tidak muncul di
    `dumpsys input`, tidak ada `addDevice`, cursor tidak bergerak.
- **Sebab:** EventHub Android hanya mengklasifikasikan kelas cursor (mouse)
  kalau device punya `REL_X/REL_Y`. `sunxi-ir-uinput` (remote asli) punya REL
  dan diadopsi dengan `Cursor Input Mapper` — bukti Aturan ini.
- Mouse **relatif** jalan tapi tidak presisi (akselerasi pointer,
  `PointerVelocityControlParameters` scale 1.28–3.0, tidak bisa dimatikan:
  `settings put system pointer_speed` / `tv_mousemode_*` **TIDAK ngefek**).

### 2.3 ✅ YANG BERHASIL: Digitizer/Touchscreen virtual (Opsi 2)
- Descriptor **Usage Page 0x0D (Digitizer), Usage 0x04 (Touch Screen)**,
  Finger + Tip Switch + In Range, X/Y 16-bit 0..32767 `Input(Data,Var,Abs)`,
  report 5 byte: `[bit0 tip, bit1 in-range][X lo][X hi][Y lo][Y hi]`.
- Kernel HID menandai `INPUT_PROP_DIRECT` → EventHub mengklasifikasikan sebagai
  **touchscreen** → diadopsi sebagai `Device N: TV Remote Virtual Pointer`,
  **`Touch Input Mapper (mode - pointer)`, `DeviceType: pointer`**,
  Motion Ranges X 0..1279 / Y 0..719 (sudah diskalakan Android, 1:1).
- **Koordinat absolut presisi sempurna** — tes overlay `pointer_location`:
  sentuh (400,300) → crosshair muncul tepat di kolom 400, baris 300.
- **Terbukti membuka aplikasi:**
  - Tap (640,150) di Settings → `com.twd.setting.module.projector.ProjectorActivity` ✅
  - Double-tap (640,450) di launcher → `com.softwinner.TvdFileManager.MainUI` ✅
- **Launcher `twdlaunchernet` butuh tap 2x** (tap pertama memunculkan kursor).
  App biasa cukup 1x. Ini perilaku launcher, BUKAN bug daemon.

### 2.4 Jebakan kernel: ABS hanya dikirim saat nilai BERUBAH
- `C 1` (tekan di posisi tersimpan) menghasilkan `BTN_TOUCH DOWN` **tanpa**
  `ABS_X/ABS_Y` → sentuhan tanpa koordinat → diabaikan framework.
- `D x y` (tekan + koordinat dalam SATU laporan) menghasilkan
  `BTN_TOUCH DOWN + ABS_X + ABS_Y` ✅ — inilah cara kerja tap yang benar.
- Protocol `T x y` di daemon = `D` disusul `C 0` setelah 50ms.

### 2.5 Wajib: thread pembaca `/dev/uhid`
- Kalau daemon tidak pernah **membaca** `/dev/uhid`, kernel menumpuk
  GET_REPORT/SET_REPORT → dmesg: `hid-generic ...: Output queue is full`
  (berulang tiap detik). Bukan fatal tapi ganggu log & bisa macet.
- Solusi di `vmoused.c`: thread `uhid_reader` membalas
  `UHID_GET_REPORT` (err=-EIO) dan `UHID_SET_REPORT` (err=0).

### 2.6 Launcher & Settings
- Launcher: `com.twd.twdlaunchernet/.MainActivity`
- `com.android.settings` tidak bisa dibuka langsung (Error type 3);
  setelan ada di `com.twd.setting/.MainActivity` (bisa `am start`).

### 2.7 ✅ Auto-setup E2E TERBUKTI (2026-10-22) — jalur relay server adb lokal

> **KOREKSI 2026-10-31 — baca 2.13. Diagnosis "kunci misterius K" di bawah ini SALAH.**
> Kunci app memang ter-authorize; jalur langsung bisa jalan setelah 2 bug app difix.

**Akar bug handshake ADB (TERBUKTI via proxy MITM di 127.0.0.1:5556):**
signature pakai `~/.android/adbkey` **valid secara kriptografi tetapi DITOLAK
adbd** — kunci itu tidak ada di authorized-keys (framework-side) proyektor.
Client asli (adb CLI) memakai **kunci misterius K** yang diizinkan proyektor;
signature-nya tidak terverifikasi di bawah adbkey dengan skema apa pun
(SHA1/SHA256/SHA512 ± DigestInfo, semua offset modulus — sudah dicek semua).
Token auth **per-koneksi** (replay signature lama tetap ditolak). Pubkey yang
dikirim app → adbd diam (dialog "Allow USB debugging?" menunggu klik di layar
proyektor — mustahil tanpa remote).

**Solusi yang DIPAKAI app sekarang — numpang server adb lokal (127.0.0.1:5037):**
1. `host:connect:<ip>:5555` (lalu **tutup socket query itu** — koneksi host sekali pakai)
2. socket baru → `host:transport:<ip:port>` → `OKAY` → `shell:` → `OKAY`
3. setelah itu **stdin/stdout mentah** (shell v1 legacy)
- **JANGAN kirim `A_OPEN` setelah transport** → langsung EOF.
- `host:connect` **asinkron** → transport pertama sering `FAIL ... not found` →
  **retry** (app: 8×600ms) — ini normal, bukan rusak.
- Server kadang menyelipkan **frame informasi len-hex** (mis.
  `already connected to ...`) sebelum/ sesudah `OKAY` → parser query harus
  menelannya sampai ketemu `OKAY`/`FAIL` (lihat `AdbClient.srvExchange`).
- Urutan fallback `MainActivity.doConnect`: **langsung (kunci embedded) dulu** →
  gagal → `connectViaLocalServer`.

### 2.8 ⚠️ `stty -echo` di shell proyektor = MERUSAK — JANGAN DIPAKAI

Terbukti empiris via relay: setelah mengeksekusi `stty -echo`, **semua huruf
besar yang dikirim berubah jadi huruf kecil** (echo line pty `echo CASE` →
`echo case`, output juga lowercase). Tanpa stty, case utuh. Dampaknya marker
`exec()` tak pernah cocok → timeout diam-diam. **Solusi: jangan kirim stty
sama sekali**; noise baris echo ditangani parser `AdbClient.exec()` (cari teks
perintah di buffer → buang sampai akhir baris, lalu ambil output sesudahnya).

### 2.9 ⚠️ Shell proyektor aritmatika 32-bit (ARM32)

`$((91995178140174))` → `1273627150` (= mod 2³²). Jangan bikin marker/token dari
`$((nanoTime))` — **pakai `printf '__R%s__' <token>`** (marker lengkap cuma ada
di output, baris echo cuma berisi `__R%s__`, jadi tak disalahartikan sbg balasan).

### 2.10 Intent extras untuk otomasi (tanpa tap UI)

```
am start -n com.projector.tvremote/.MainActivity \
  --es host 10.193.162.28 --es mode adb --ez autoconnect true
```
Opsional: `--es port 9877`, `--es mode service`. `mode=adb` → port 5555 selalu.
Log debugging: `logcat -s AdbHs` (tiap langkah `ensureDaemons`/`pushB64` kini
di-log; dulu `catch (Exception ignored)` menelan semuanya).

### 2.11 adbd proyektor: single-client & first-connection quirk

Hanya 1 koneksi shell pada satu waktu — kalau Termux/PC masih megang `adb shell`,
app akan rebutan. Koneksi pertama ke adbd kadang aneh (AUTH/transport) →
putuskan semua entri lama (`adb disconnect`) sebelum tes E2E.

### 2.13 ✅ KOREKSI 2026-10-31: auth langsung BISA — 2 bug app + insiden adb mati

- Kunci app/Termux memang authorized: `/data/misc/adb/adb_keys` berisi kunci yang
  sama dengan `~/.android/adbkey.pub` (beda 1 byte newline). Klaim "kunci
  misterius K" di 2.7 salah.
- **BUG UTAMA (ketemu 2026-10-31 siang, lewat proxy + verifikasi matematis):**
  adb **tidak meng-hash token**. Signature = PKCS#1 v1.5 dengan
  `DigestInfo(SHA1-OID) + token MENTAH`:
  `30 21 30 09 06 05 2b 0e 03 02 1a 05 00 04 14` + 20 byte token, lalu RSA
  private op. App memakai `SHA1withRSA` (token di-hash dulu) → signature selalu
  ditolak adbd (adbd kirim token baru). Terbukti: signature hasil skema baru
  **byte-identik** dengan signature adb CLI untuk token yang sama.
  Fix: `AdbClient.signToken()` pakai `NONEwithRSA` (fallback
  `Cipher RSA/ECB/PKCS1Padding` + private key).
- Bug lain (juga difix di 1.0.1):
  1. `AdbClient.writePacket` pakai **CRC32**, padahal protokol adb pakai
     **jumlah byte (sum)** → packet dibuang diam-diam oleh adbd.
  2. Setelah signature ditolak: token berikutnya → kirim **AUTH RSAPUBLICKEY
     (arg0=3)** payload `<base64-blob> <user>` (flag `sentSig`), bukan
     signature lagi.
  3. Timeout handshake 5 dtk → **20 dtk**.
- **Hasil E2E 2026-10-31 12:09:** app konek **langsung tanpa Termux** →
  `CNXN diterima — handshake sukses` → `ensureDaemons` (`needPush=false`,
  9877/9878 sehat).
- **Dialog otorisasi:** koneksi pertama setelah recovery tetap memunculkan
  **"Allow USB debugging?"** di layar proyektor → harus di-ACC manual (centang
  "Always allow from this computer"). Setelah itu koneksi berikutnya langsung
  lolos tanpa dialog.
- **Performa auth:** adbd proyektor bisa lambat membalas; kalau koneksi
  ditinggal timeout, adbd bisa "wedged" (TCP diterima, tak ada token) sampai
  koneksi lama benar-benar hilang. Solusi praktis: putuskan semua koneksi lain
  (`adb disconnect`), tunggu beberapa detik, baru sambungkan klien berikutnya.

**⚠️ INSIDEN 2026-10-31 (jangan diulang):** untuk me-restart adbd yang wedged,
dijalankan `settings put global adb_enabled 0` lewat shell daemon 9877.
Akibatnya adbd mati **dan membunuh semua shell child** (daemon 9877/9878 ikut
mati). ADB jadi OFF (setting persisten), `service.adb.tcp.port` non-persist.
Yang masih hidup: port 5000/7000/7100/7700 (AirPlay/streaming); mDNS cuma
`_airplay`/`_raop` → tidak ada remote-service untuk recovery.

**Recovery (SUDAH dilakukan 2026-10-31):**
1. Colok keyboard/mouse USB ke proyektor (atau remote TV via HDMI-CEC).
2. Settings → Device Preferences → About → tap **Build** 7× → Developer options.
3. Nyalakan **Wireless debugging** (kirim IP:port + kode pairing) atau
   **ADB over network** (langsung port 5555).
4. Setelah dapat shell:
   `setprop persist.adb.tcp.port 5555; setprop service.adb.tcp.port 5555`
   lalu jalankan app mode ADB → auto-setup menghidupkan daemon lagi.

Hasil 2026-10-31: user menyalakan **ADB over network** → port 5555 terbuka →
`persist.adb.tcp.port=5555` terset (tahan reboot) → daemon 9877/9878 dijalankan
ulang → user ACC dialog di proyektor → app konek langsung. Selesai.

**JANGAN** pernah `settings put global adb_enabled 0` lewat daemon: itu mematikan
satu-satunya jalur recovery jarak jauh.

### 2.12 Pemetaan bug "app terhubung tapi file tidak ter-push" (arsip diagnosis)

- `exec()` pertama gagal/false-echo → `ensureDaemons` abort diam → port tutup.
- Gejala sama persis dengan **jaringan putus di tengah tes** (2026-10-2200:47:
  router renumber, SEMUA transport offline serentak). Cek dulu konektivitas
  (`ping <ip proyektor>`) sebelum menyimpulkan bug kode.

---

## 3. File-file penting

```
~/tvremote/
├── vmoused.c              # SUMBER daemon touchscreen virtual (SELESAI, jalan)
├── vmoused32              # binary armv7 hasil compile
├── install_vmoused.sh     # installer daemon (port 9878) + auto-restart loop
├── install_daemon.sh      # installer shell daemon (port 9877)
├── src/com/projector/tvremote/
│   ├── MainActivity.java  # app UI (touchpad ABSOLUT, sudah sinkron protokol)
│   ├── AdbClient.java     # klien protokol ADB mentah
│   └── AdbKey.java        # private key ADB ter-embed
├── AndroidManifest.xml
├── build_apk.sh           # aapt2 -> javac -> d8 -> zip -> apksigner
├── build/android.jar      # API 30
├── build/out/tvremote.apk # APK jadi & ter-sign (com.projector.tvremote)
├── findcursor.py          # deteksi diff posisi dari screencap raw (dibuat ulang)
└── HANDOFF.md             # file ini
```

---

## 4. Protokol daemon port 9878 (SEKARANG — touchscreen)

Satu perintah per baris, balasan `OK`/`PONG`/`BYE`/`ERR`:

| Perintah | Arti |
|---|---|
| `A <x> <y>` | catat posisi piksel layar (1280x720); di touchscreen tanpa efek visual |
| `T <x> <y>` | **tap** di (x,y): tekan+50ms+lepas — perintah utama |
| `K` | tap di posisi `A` terakhir |
| `C <1\|0>` | tahan / lepas di posisi terakhir (untuk drag) |
| `D <x> <y>` | tekan DAN pindah ke (x,y) dalam satu laporan |
| `S <n>` | no-op (single-touch tanpa scroll) |
| `P` / `Q` | ping / keluar |

**Kalau protokol daemon berubah, WAJIB sinkronkan `mouse(...)` di MainActivity.**

---

## 5. Cara compile (JANGAN pakai cara lain — ini yang berhasil)

```bash
cd ~/tvremote
clang --target=armv7a-linux-androideabi21 \
  --sysroot=$PREFIX/arm-linux-androideabi \
  -isystem $PREFIX/include -isystem $PREFIX/include/arm-linux-androideabi \
  -march=armv7-a -mfloat-abi=softfp -mfpu=vfpv3-d16 \
  -O2 -Wall -o vmoused32 vmoused.c
```

Prasyarat: `ndk-multilib`, `ndk-multilib-native-static`, `ndk-sysroot`.
Jangan `-static`. Jangan `--sysroot=$PREFIX/opt/ndk-multilib/...`.

Deploy:
```bash
S="-s 10.80.108.28:5555"
adb $S push vmoused32 /data/local/tmp/vmoused32
adb $S shell "sh /data/local/tmp/install_vmoused.sh 9878"
```

Build APK: `cd ~/tvremote && ./build_apk.sh` → `build/out/tvremote.apk`
Install ke HP: `adb -s 10.80.108.48:45175 install -r build/out/tvremote.apk`
(port HP random tiap sesi wireless debugging — cek `adb devices` / scan).

---

## 6. Cara verifikasi touchpad (kalau ada yang ditanya)

```bash
# 1. device terdaftar & diadopsi InputReader?
adb -s 10.80.108.28:5555 shell "cat /proc/bus/input/devices | grep -A7 'TV Remote'"
adb -s 10.80.108.28:5555 shell "dumpsys input | grep -A8 'TV Remote'"   # harus ada Touch Input Mapper

# 2. tes tap presisi (overlay koordinat sentuh):
adb -s 10.80.108.28:5555 shell "settings put system pointer_location 1"
#    kirim D 400 300 lalu C 0, screencap, cek crosshair di (400,300)
#    (matikan lagi: settings put system pointer_location 0)

# 3. tes fungsional:
#    am start -n com.twd.setting/.MainActivity
#    kirim T 640 150 -> fokus harus pindah ke ProjectorActivity
```

---

## 7. Jebakan yang pernah bikin salah

1. Local bash expand `$(...)` di dalam `adb shell "..."` → pakai `\$` atau quote.
2. `ps -A` di proyektor cuma nama proses → pakai `pgrep -f`.
3. `zip` Termux tidak suka path relatif `-j` → `build_apk.sh` pakai path absolut.
4. Jangan `-static` di clang cross-compile.
5. Proyektor **tidak punya Python**. Interpreter: `sh`, `toybox`.
6. `uiautomator dump` gagal di launcher kustom ("could not get idle state").
7. **Power keyevent (26) mematikan display proyektor** — hati-hati.
8. Daemon **mati saat reboot** → jalankan ulang installer via ADB.
9. IP proyektor/HP bisa berubah; app punya tombol **Scan**.
10. Model AI di session ini **tidak bisa melihat gambar** — verifikasi layar
    secara numerik (diff piksel numpy) atau lewat `dumpsys window` fokus.
11. `screencap` raw (tanpa `-p`) punya header 16 byte sebelum data RGBA.
12. Termux tidak punya PIL/scipy; numpy ADA. Decoder PNG manual di git history
    (session sebelumnya) kalau perlu.
13. **Jangan kirim `stty -echo`** ke shell proyektor → translasi huruf besar→kecil
    semua input (lihat 2.8). Marker `exec()` wajib pakai `printf`, bukan `$((...))`.
14. Server adb (Termux) bisa mengirim **frame info tambahan** di antara query —
    parser `srvExchange` harus menelan sampai `OKAY`/`FAIL`.
15. `host:connect` async → `host:transport` pertama sering `FAIL not found` —
    **retry**, jangan panik.
16. Sebelum tes E2E: `adb kill-server && adb start-server` + `adb disconnect`
    entri stale (device offline di server bikin koneksi host ditutup prematur).
17. **Kill daemon**: jangan `pgrep -f` pattern yang ada di cmdline shell-mu
    sendiri — cocokkan argv[0] via `/proc/$p/cmdline`.
18. **Mode Service tapi port 5555**: field port bisa tersisa dari sesi mode ADB →
    app "Terhubung" tapi nyambung ke adbd (bukan daemon), perintah hilang.
    Sekarang intent `mode=tcp/service` memaksa port 9877 dan `onCreate` menolak
    port 5555 untuk mode Service.
19. **WiFi proyektor saat boot**: kalau `settings get global wifi_on` = 0 sebelum
    reboot, WiFi TIDAK nyala sampai app vendor (`com.twd.setting`) menyalakannya
    ~2 menit setelah boot. Dengan `wifi_on=1`, SystemUI menyalakan WiFi ~7 dtk
    setelah WifiService start → adb 5555 hidup ~26 dtk setelah reboot.

---

## 8. Sisa pekerjaan (opsional, sudah bukan bloker)

- [x] **Auto-setup proyektor baru dari HP** — SELEA & terbukti E2E 2026-10-22
      (push md5 identik → installer → 9877/9878 LISTEN → device input terdaftar).
- [ ] **Proyektor #2 "tanpa Termux sama sekali"** — belum diputuskan jalurnya:
      (a) pairing wireless debugging proyektor (butuh layar utk lihat kode
      pairing — remote hilang?), atau (b) sekali pakai Termux/AI utk
      `service.adb.tcp.port=5555` + `adb start-server`, selanjutnya app jalan
      sendiri selama server adb hidup.
- [ ] **Keamanan**: port 9877 (shell uid shell!) dan 9878 **tanpa autentikasi**,
      terbuka ke seluruh LAN. Sudah pernah ditawarkan ke user, belum diimplement.
- [ ] Persistensi ADB TCP setelah reboot proyektor belum dikonfirmasi.
- [ ] Scroll di touchpad (sekarang tombol scroll pakai `input swipe` via shell).
- [ ] Drag panjang dari touchpad (protocol `C 1` → `D` berulang → `C 0` sudah
      tersedia di daemon; UI belum menggunakannya).

---

*Dibuat: 2026-10-20. Diperbarui setelah Opsi 1 (mouse absolut murni) terbukti
gagal di EventHub Android dan Opsi 2 (digitizer touchscreen) terbukti jalan
presisi 1:1. Diperbarui lagi 2026-10-22: akar bug auth ADB (kunci misterius K,
framework-side keys, dialog pubkey silent), jalur relay `host:transport` +
shell v1 terbukti E2E untuk auto-setup penuh, jebakan `stty -echo` (lowercase),
aritmatika shell 32-bit, frame info server adb, dan renumber jaringan
10.80.108.x → 10.193.162.x.*
