// vmoused.c - Virtual pointer via /dev/uhid untuk Android TV.
//
// DUA device sekaligus:
//   1. TOUCHSCREEN ABSOLUT (Digitizer 0x0D/0x04, INPUT_PROP_DIRECT) — tap presisi.
//      Kernel hanya mengirim ABS saat nilai berubah, jadi tekan harus
//      menyertakan koordinat dalam SATU laporan (perintah D/T).
//   2. MOUSE RELATIF (Generic Desktop Mouse, REL_X/REL_Y + wheel) — digeser
//      seperti touchpad laptop: kursor bergerak, scroll roda. Android
//      mengadopsinya sebagai Cursor Input Mapper. Catatan: pointer Android
//      punya akselerasi (tidak bisa dimatikan), jadi gerakan relatif TIDAK 1:1.
// (Mouse absolut murni TANPA REL terbukti GAGAL — EventHub tidak mengadopsi.)
//
// Thread terpisah membaca tiap fd /dev/uhid untuk membalas GET_REPORT/SET_REPORT,
// supaya tidak terjadi "Output queue is full" di kernel.
//
// Protokol TCP (satu perintah per baris):
//   --- mouse relatif ---
//   M <dx> <dy>    gerakkan kursor relatif (px, boleh negatif)
//   B <0|1|2>      tombol mouse: 0 lepas, 1 kiri tahan, 2 kanan tahan
//   S <n>          scroll roda (negatif = ke atas)
//   --- touchscreen absolut ---
//   A <x> <y>      catat posisi piksel layar (untuk K-ts)
//   T <x> <y>      TAP presisi di (x,y)
//   C <1|0>        tahan/lepas sentuh di posisi terakhir (touchscreen)
//   D <x> <y>      tekan + pindah ke (x,y) dalam satu laporan
//   --- umum ---
//   K              klik kiri penuh di posisi kursor (mouse)
//   R              klik kanan penuh (mouse)
//   P              ping
//   Q              keluar
// Balasan per baris: "OK" / "PONG" / "BYE" / "ERR"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <pthread.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <linux/uhid.h>

static int ts_fd = -1;   // touchscreen absolut
static int ms_fd = -1;   // mouse relatif
static int SCR_W = 1280, SCR_H = 720;

// ---------------------------------------------------------------------------
// Descriptor 1: Digitizer absolut (Touch Screen), tanpa Report ID.
// Report: [bit0 tip switch, bit1 in range][X lo][X hi][Y lo][Y hi] = 5 byte.
// ---------------------------------------------------------------------------
static const unsigned char TS_DESC[] = {
    0x05, 0x0D,        // Usage Page (Digitizer)
    0x09, 0x04,        // Usage (Touch Screen)
    0xA1, 0x01,        // Collection (Application)
    0x09, 0x22,        //   Usage (Finger)
    0xA1, 0x02,        //   Collection (Logical)
    0x09, 0x42,        //     Usage (Tip Switch)
    0x09, 0x32,        //     Usage (In Range)
    0x15, 0x00,        //     Logical Minimum (0)
    0x25, 0x01,        //     Logical Maximum (1)
    0x75, 0x01,        //     Report Size (1)
    0x95, 0x02,        //     Report Count (2)
    0x81, 0x02,        //     Input (Data,Var,Abs)
    0x95, 0x06,        //     Report Count (6)
    0x75, 0x01,        //     Report Size (1)
    0x81, 0x01,        //     Input (Const)          <- padding 6 bit
    0x05, 0x01,        //     Usage Page (Generic Desktop)
    0x09, 0x30,        //     Usage (X)
    0x09, 0x31,        //     Usage (Y)
    0x15, 0x00,        //     Logical Minimum (0)
    0x26, 0xFF, 0x7F,  //     Logical Maximum (32767)
    0x75, 0x10,        //     Report Size (16)
    0x95, 0x02,        //     Report Count (2)
    0x81, 0x02,        //     Input (Data,Var,Abs)
    0xC0,              //   End Collection
    0xC0               // End Collection
};

// ---------------------------------------------------------------------------
// Descriptor 2: Mouse relatif (Generic Desktop) + wheel.
// Report: [buttons bit0..2][X 8-bit signed rel][Y 8-bit signed rel][wheel 8-bit]
//       = 4 byte.
// ---------------------------------------------------------------------------
static const unsigned char MS_DESC[] = {
    0x05, 0x01,        // Usage Page (Generic Desktop)
    0x09, 0x02,        // Usage (Mouse)
    0xA1, 0x01,        // Collection (Application)
    0x09, 0x01,        //   Usage (Pointer)
    0xA1, 0x00,        //   Collection (Physical)
    0x05, 0x09,        //     Usage Page (Button)
    0x19, 0x01,        //     Usage Minimum (Button 1)
    0x29, 0x03,        //     Usage Maximum (Button 3)
    0x15, 0x00,        //     Logical Minimum (0)
    0x25, 0x01,        //     Logical Maximum (1)
    0x95, 0x03,        //     Report Count (3)
    0x75, 0x01,        //     Report Size (1)
    0x81, 0x02,        //     Input (Data,Var,Abs)
    0x95, 0x01,        //     Report Count (1)
    0x75, 0x05,        //     Report Size (5)
    0x81, 0x01,        //     Input (Const)          <- padding 5 bit
    0x05, 0x01,        //     Usage Page (Generic Desktop)
    0x09, 0x30, 0x09, 0x31, 0x09, 0x38, // Usage X, Y, Wheel
    0x15, 0x81,        //     Logical Minimum (-127)
    0x25, 0x7F,        //     Logical Maximum (127)
    0x75, 0x08,        //     Report Size (8)
    0x95, 0x03,        //     Report Count (3)
    0x81, 0x06,        //     Input (Data,Var,Rel)   <-- REL, bukan Abs!
    0xC0,              //   End Collection
    0xC0               // End Collection
};

static int to_abs_x(int px) { return (int)((long)px * 32767 / (SCR_W - 1)); }
static int to_abs_y(int px) { return (int)((long)px * 32767 / (SCR_H - 1)); }

// posisi absolut touchscreen terakhir
static int last_abs_x = 16384, last_abs_y = 16384;
static int last_px = 640, last_py = 360;

// tombol mouse yang sedang ditekan (bit0 kiri, bit1 kanan)
static int ms_buttons = 0;

static void uhid_touch(int tip, int x, int y) {
    if (ts_fd < 0) return;
    if (x < 0) x = 0; if (x > 32767) x = 32767;
    if (y < 0) y = 0; if (y > 32767) y = 32767;
    struct uhid_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_INPUT2;
    ev.u.input2.size = 5;
    ev.u.input2.data[0] = (unsigned char)((tip ? 0x01 : 0x00) | 0x02); // tip + in-range
    ev.u.input2.data[1] = (unsigned char)(x & 0xFF);
    ev.u.input2.data[2] = (unsigned char)((x >> 8) & 0xFF);
    ev.u.input2.data[3] = (unsigned char)(y & 0xFF);
    ev.u.input2.data[4] = (unsigned char)((y >> 8) & 0xFF);
    if (write(ts_fd, &ev, sizeof(ev)) < 0) perror("TS UHID_INPUT2");
}

// laporan mouse: [buttons][dx][dy][wheel], semua signed 8-bit kecuali buttons
static void ms_report(int dx, int dy, int wheel) {
    if (ms_fd < 0) return;
    if (dx < -127) dx = -127; if (dx > 127) dx = 127;
    if (dy < -127) dy = -127; if (dy > 127) dy = 127;
    if (wheel < -127) wheel = -127; if (wheel > 127) wheel = 127;
    struct uhid_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_INPUT2;
    ev.u.input2.size = 4;
    ev.u.input2.data[0] = (unsigned char)(ms_buttons & 0x07);
    ev.u.input2.data[1] = (unsigned char)(dx & 0xFF);
    ev.u.input2.data[2] = (unsigned char)(dy & 0xFF);
    ev.u.input2.data[3] = (unsigned char)(wheel & 0xFF);
    if (write(ms_fd, &ev, sizeof(ev)) < 0) perror("MS UHID_INPUT2");
}

// --- thread pembaca /dev/uhid: balas GET_REPORT/SET_REPORT, buang event lain --
static void *uhid_reader(void *arg) {
    int fd = (int)(long)arg;
    for (;;) {
        struct uhid_event ev;
        ssize_t n = read(fd, &ev, sizeof(ev));
        if (n < 0) {
            if (errno == EAGAIN || errno == EINTR) continue;
            perror("uhid read");
            return NULL;
        }
        if (ev.type == UHID_GET_REPORT) {
            struct uhid_event r;
            memset(&r, 0, sizeof(r));
            r.type = UHID_GET_REPORT_REPLY;
            r.u.get_report_reply.id = ev.u.get_report.id;
            r.u.get_report_reply.err = -EIO;   // tidak ada feature report
            write(fd, &r, sizeof(r));
        } else if (ev.type == UHID_SET_REPORT) {
            struct uhid_event r;
            memset(&r, 0, sizeof(r));
            r.type = UHID_SET_REPORT_REPLY;
            r.u.set_report_reply.id = ev.u.set_report.id;
            r.u.set_report_reply.err = 0;
            write(fd, &r, sizeof(r));
        }
        // UHID_START/STOP/OPEN/CLOSE: abaikan
    }
    return NULL;
}

static int uhid_open(const char *name, const unsigned char *desc, size_t dsize) {
    int fd = open("/dev/uhid", O_RDWR | O_CLOEXEC);
    if (fd < 0) { perror("open /dev/uhid"); return -1; }

    struct uhid_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_CREATE2;
    strncpy((char *)ev.u.create2.name, name, sizeof(ev.u.create2.name) - 1);
    ev.u.create2.bus = 0x03;              // BUS_USB
    ev.u.create2.vendor = 0x1234;
    ev.u.create2.product = 0x5678;
    ev.u.create2.version = 1;
    ev.u.create2.rd_size = dsize;
    memcpy(ev.u.create2.rd_data, desc, dsize);

    if (write(fd, &ev, sizeof(ev)) < 0) { perror("UHID_CREATE2"); close(fd); return -1; }

    pthread_t th;
    pthread_create(&th, NULL, uhid_reader, (void *)(long)fd);
    pthread_detach(th);
    return fd;
}

static void tap_ts(int x, int y) {
    last_px = x; last_py = y;
    last_abs_x = to_abs_x(x); last_abs_y = to_abs_y(y);
    uhid_touch(1, last_abs_x, last_abs_y);
    usleep(50000);
    uhid_touch(0, last_abs_x, last_abs_y);
}

// klik mouse kiri penuh (tekan+lepas) tanpa mengubah posisi kursor
static void ms_click_left(void) {
    ms_buttons = 0x01; ms_report(0, 0, 0);
    usleep(40000);
    ms_buttons = 0x00; ms_report(0, 0, 0);
}

static void ms_click_right(void) {
    ms_buttons = 0x02; ms_report(0, 0, 0);
    usleep(40000);
    ms_buttons = 0x00; ms_report(0, 0, 0);
}

static void serve(int c) {
    char buf[8192];
    for (;;) {
        int n = read(c, buf, sizeof(buf) - 1);
        if (n <= 0) return;
        buf[n] = 0;
        char *save = NULL;
        for (char *line = strtok_r(buf, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) {
            char cmd = line[0];
            int x, y, v;
            if (cmd == 'M' && sscanf(line, "M %d %d", &x, &y) == 2) {
                // gerak relatif; kalau besar, pecah agar muat signed 8-bit
                while (x != 0 || y != 0) {
                    int sx = (x > 127) ? 127 : (x < -127) ? -127 : x;
                    int sy = (y > 127) ? 127 : (y < -127) ? -127 : y;
                    ms_report(sx, sy, 0);
                    x -= sx; y -= sy;
                }
                write(c, "OK\n", 3);
            } else if (cmd == 'B' && sscanf(line, "B %d", &x) == 1) {
                ms_buttons = x ? (x == 2 ? 0x02 : 0x01) : 0x00;
                ms_report(0, 0, 0);
                write(c, "OK\n", 3);
            } else if (cmd == 'S' && sscanf(line, "S %d", &v) == 1) {
                ms_report(0, 0, -v);   // roda mouse; arah disesuaikan app
                write(c, "OK\n", 3);
            } else if (cmd == 'A' && sscanf(line, "A %d %d", &x, &y) == 2) {
                last_px = x; last_py = y;
                last_abs_x = to_abs_x(x); last_abs_y = to_abs_y(y);
                write(c, "OK\n", 3);
            } else if (cmd == 'T' && sscanf(line, "T %d %d", &x, &y) == 2) {
                tap_ts(x, y);
                write(c, "OK\n", 3);
            } else if (cmd == 'K') {
                ms_click_left();
                write(c, "OK\n", 3);
            } else if (cmd == 'R') {
                ms_click_right();
                write(c, "OK\n", 3);
            } else if (cmd == 'C' && sscanf(line, "C %d", &x) == 1) {
                uhid_touch(x ? 1 : 0, last_abs_x, last_abs_y);
                write(c, "OK\n", 3);
            } else if (cmd == 'D' && sscanf(line, "D %d %d", &x, &y) == 2) {
                last_px = x; last_py = y;
                last_abs_x = to_abs_x(x); last_abs_y = to_abs_y(y);
                uhid_touch(1, last_abs_x, last_abs_y);
                write(c, "OK\n", 3);
            } else if (cmd == 'P') {
                write(c, "PONG\n", 5);
            } else if (cmd == 'Q') {
                write(c, "BYE\n", 4);
                close(c); close(ts_fd); close(ms_fd); exit(0);
            } else {
                write(c, "ERR\n", 4);
            }
        }
    }
}

int main(int argc, char **argv) {
    int port = argc > 1 ? atoi(argv[1]) : 9878;
    if (argc > 2) SCR_W = atoi(argv[2]);
    if (argc > 3) SCR_H = atoi(argv[3]);
    fprintf(stderr, "layar %dx%d, sizeof ev=%zu\n", SCR_W, SCR_H, sizeof(struct uhid_event));

    ts_fd = uhid_open("TV Remote Virtual Touchscreen", TS_DESC, sizeof(TS_DESC));
    if (ts_fd < 0) { fprintf(stderr, "gagal buat touchscreen\n"); return 1; }
    ms_fd = uhid_open("TV Remote Virtual Mouse", MS_DESC, sizeof(MS_DESC));
    if (ms_fd < 0) { fprintf(stderr, "gagal buat mouse\n"); return 1; }

    fprintf(stderr, "touchscreen absolut + mouse relatif siap di port %d\n", port);
    fflush(stderr);

    int srv = socket(AF_INET, SOCK_STREAM, 0);
    int on = 1;
    setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &on, sizeof(on));
    struct sockaddr_in a;
    memset(&a, 0, sizeof(a));
    a.sin_family = AF_INET; a.sin_addr.s_addr = INADDR_ANY; a.sin_port = htons(port);
    if (bind(srv, (struct sockaddr *)&a, sizeof(a)) < 0) { perror("bind"); return 1; }
    listen(srv, 4);

    for (;;) {
        int c = accept(srv, NULL, NULL);
        if (c < 0) { if (errno == EINTR) continue; perror("accept"); break; }
        serve(c);
        close(c);
    }
    return 0;
}
