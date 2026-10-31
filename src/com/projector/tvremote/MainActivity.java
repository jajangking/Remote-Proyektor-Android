package com.projector.tvremote;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // ===== keycode Android (KEYCODE_*) =====
    static final int KC_BACK=4, KC_HOME=3, KC_MENU=82, KC_POWER=26,
        KC_UP=19, KC_DOWN=20, KC_LEFT=21, KC_RIGHT=22, KC_CENTER=23,
        KC_VOLUP=24, KC_VOLDOWN=25, KC_MUTE=164,
        KC_PLAYPAUSE=85, KC_NEXT=87, KC_PREV=88, KC_FF=90, KC_REW=89,
        KC_SEARCH=84, KC_GUIDE=172, KC_INFO=165, KC_CHUP=166, KC_CHDOWN=167,
        KC_INPUT=178, KC_TRACK=222, KC_ENTER=66, KC_SPACE=62, KC_TAB=61,
        KC_ESC=111, KC_PAGEUP=92, KC_PAGEDOWN=93, KC_SLEEP=223, KC_WAKEUP=224;

    private enum Mode { ADB, TCP, MOUSE }

    private volatile Mode mode = Mode.TCP;
    private volatile String host = "";
    private volatile int port = 0;
    private volatile boolean connected = false;

    private Socket sock;
    private DataOutputStream out;
    private DataInputStream in;
    private AdbClient adb;
    private String adbJalur = "";

    // koneksi mouse virtual (port 9878) - terpisah dari koneksi shell
    private Socket mSock;
    private DataOutputStream mOut;
    private volatile boolean mouseConnected = false;
    private int mousePort = 9878;

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private EditText etHost, etPort, etText;
    private Button btnConnect;
    private RadioGroup rg;
    private RadioButton rAdb, rTcp;

    // ================= lifecycle =================

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        // ingat IP/port proyektor terakhir — supaya buka app langsung tinggal
        // "Hubungkan" (dulu kosong → connect jatuh ke loopback "::1" → error membingungkan)
        SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        String lastHost = sp.getString("host", "");
        if (!lastHost.isEmpty()) etHost.setText(lastHost);
        String lastPort = sp.getString("port", "");
        if (!lastPort.isEmpty()) etPort.setText(lastPort);
        // port 5555 = ADB; kalau tersisa dari sesi mode ADB sedangkan mode awal
        // Service (default), jangan dipakai — app akan "tersambung" ke adbd, bukan daemon
        if ("5555".equals(etPort.getText().toString().trim())) etPort.setText("9877");
        setStatus("Belum terhubung", Color.RED);
        detectLocalIp();
        handleIntent();   // extras intent (otomasi) menimpa penyimpanan
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent();
    }

    /**
     * Dukungan otomatisasi — bisa dikendalikan dari luar tanpa ketik/tap:
     *   am start -n com.projector.tvremote/.MainActivity \
     *       --es host 10.80.108.28 --es mode adb --ez autoconnect true
     */
    private void handleIntent() {
        Intent i = getIntent();
        if (i == null) return;
        String h = i.getStringExtra("host");
        if (h != null && !h.isEmpty()) etHost.setText(h);
        String p = i.getStringExtra("port");
        if (p != null && !p.isEmpty()) etPort.setText(p);
        String m = i.getStringExtra("mode");
        if ("adb".equalsIgnoreCase(m)) { rg.check(rAdb.getId()); etPort.setText("5555"); }
        else if ("service".equalsIgnoreCase(m) || "tcp".equalsIgnoreCase(m)) {
            rg.check(rTcp.getId());
            etPort.setText("9877");
        }
        if (i.getBooleanExtra("autoconnect", false))
            ui.postDelayed(() -> btnConnect.performClick(), 700);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        net.execute(this::doDisconnect);
    }

    // ================= koneksi =================

    private void doConnect() {
        android.util.Log.i("AdbHs", "doConnect dipanggil, mode awal=" + mode);
        try {
            doDisconnect();
            host = etHost.getText().toString().trim();
            if (host.isEmpty()) {
                // tanpa guard ini InetSocketAddress("", ...) jatuh ke ::1 (loopback
                // HP sendiri) → "failed to connect to ip6-localhost/::1" yang membingungkan
                ui.post(() -> setStatus("IP proyektor belum diisi — ketik IP-nya atau tekan Scan", Color.RED));
                return;
            }
            // simpan supaya buka app berikutnya tinggal Hubungkan
            getSharedPreferences("cfg", MODE_PRIVATE).edit()
                    .putString("host", host)
                    .putString("port", etPort.getText().toString().trim())
                    .apply();
            if (mode == Mode.ADB) {
                port = 5555;    // ADB selalu 5555 — jangan pakai field port (itu untuk Service 9877)
            } else {
                port = Integer.parseInt(etPort.getText().toString().trim());
            }
            if (mode == Mode.ADB) {
                adb = new AdbClient();
                adb.setAuth(AdbKey.PRIVATE_PEM, AdbKey.PUBLIC_LINE);
                try {
                    adb.connect(host, port);                    // jalur 1: langsung, kunci embedded
                    adbJalur = "langsung";
                } catch (Exception e1) {
                    android.util.Log.i("AdbHs", "jalur langsung gagal (" + e1.getMessage()
                            + ") -> coba numpang server adb lokal");
                    try { adb.close(); } catch (Exception ignored) {}
                    adb = new AdbClient();
                    adb.connectViaLocalServer(host, port);      // jalur 2: numpang server adb HP (Termux)
                    adbJalur = "via server adb HP";
                }
                connected = adb.isConnected();
            } else {
                sock = new Socket();
                sock.connect(new InetSocketAddress(host, port), 5000);
                sock.setTcpNoDelay(true);
                out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()));
                in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
                connected = true;
                new Thread(this::tcpReader, "tcp-reader").start();
            }
            final boolean c = connected;
            if (c && mode == Mode.ADB) {
                ui.post(() -> setStatus("ADB tersambung — siapkan daemon proyektor...", Color.parseColor("#1565C0")));
                ensureDaemons();                       // hidupkan ulang daemon kalau proyektor reboot
            }
            if (c) {
                connectMouse(true);
                if (!mouseConnected && mode == Mode.TCP) {
                    // service 9877 jalan tapi vmoused mati -> install ulang via shell daemon
                    send("sh /data/local/tmp/install_vmoused.sh 9878 >/dev/null 2>&1 &");
                    try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
                    connectMouse(true);
                }
            }
            ui.post(() -> {
                if (c) {
                    String extra = mouseConnected ? " + mouse" : " (tanpa mouse)";
                    if (mode == Mode.ADB) extra += " [" + adbJalur + "]";
                    setStatus("Terhubung " + mode + " " + host + ":" + port + extra, Color.parseColor("#2E7D32"));
                    btnConnect.setText("Putus");
                }
                else setStatus("Gagal terhubung", Color.RED);
            });
        } catch (Exception e) {
            android.util.Log.i("AdbHs", "doConnect EXCEPTION: " + e);
            connected = false;
            String msg = "Error: " + e.getMessage();
            if (mode == Mode.ADB)
                msg += "\n(catatan: proyektor hanya melayani 1 koneksi ADB — tutup koneksi ADB lain dulu, mis. dari Termux/PC)";
            final String m = msg;
            ui.post(() -> setStatus(m, Color.RED));
        }
    }

    private void doDisconnect() {
        connected = false;
        mouseConnected = false;
        try { if (adb != null) adb.close(); } catch (Exception ignored) {}
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
        try { if (mSock != null) mSock.close(); } catch (Exception ignored) {}
        adb = null; sock = null; out = null; in = null; mSock = null; mOut = null;
    }

    /** Buka koneksi kedua ke daemon mouse virtual (port 9878). */
    private void connectMouse() { connectMouse(false); }

    private void connectMouse(boolean quiet) {
        try {
            mSock = new Socket();
            mSock.connect(new InetSocketAddress(host, mousePort), 3000);
            mSock.setTcpNoDelay(true);
            mOut = new DataOutputStream(new BufferedOutputStream(mSock.getOutputStream()));
            mouseConnected = true;
        } catch (Exception e) {
            mouseConnected = false;
            if (!quiet) ui.post(() -> toast("Mouse virtual tidak aktif di port " + mousePort +
                    " (reboot proyektor? sambung via ADB 1x untuk auto-setup)"));
        }
    }

    /**
     * Sembuhkan daemon proyektor lewat ADB.
     * Kalau file belum ada (proyektor baru/factory reset), kirim otomatis dari APK.
     * Kalau port mati (habis reboot), jalankan ulang installernya.
     */
    private void ensureDaemons() {
        try {
            // 1. kirim file kalau belum ada
            boolean needPush = !adb.exec("[ -x /data/local/tmp/vmoused32 ] && echo Y", 3000).contains("Y");
            android.util.Log.i("AdbHs", "ensureDaemons: needPush=" + needPush);
            if (needPush) {
                ui.post(() -> setStatus("Proyektor baru: kirim daemon...", Color.parseColor("#1565C0")));
                pushB64("/data/local/tmp/vmoused32", DaemonPayload.VMOUSED32_B64, true);
                pushB64("/data/local/tmp/install_daemon.sh", DaemonPayload.INSTALL_DAEMON_B64, true);
                pushB64("/data/local/tmp/install_vmoused.sh", DaemonPayload.INSTALL_VMOUSED_B64, true);
            }
            // 2. hidupkan daemon kalau portnya mati
            // (ambil hanya digit dari balasan — kalau prompt terminal ikut nyangkut, tidak ganggu)
            String s1 = adb.exec("netstat -lnt 2>/dev/null | grep -c ':9877 '", 4000);
            android.util.Log.i("AdbHs", "ensureDaemons: cek9877='" + s1 + "'");
            if (!s1.replaceAll("\\D", "").equals("1")) {
                android.util.Log.i("AdbHs", "ensureDaemons: start install_daemon 9877");
                adb.exec("sh /data/local/tmp/install_daemon.sh 9877 >/dev/null 2>&1", 15000);
            }
            String s2 = adb.exec("netstat -lnt 2>/dev/null | grep -c ':9878 '", 4000);
            android.util.Log.i("AdbHs", "ensureDaemons: cek9878='" + s2 + "'");
            if (!s2.replaceAll("\\D", "").equals("1")) {
                android.util.Log.i("AdbHs", "ensureDaemons: start install_vmoused 9878");
                adb.exec("sh /data/local/tmp/install_vmoused.sh 9878 >/dev/null 2>&1", 15000);
            }
            android.util.Log.i("AdbHs", "ensureDaemons: selesai");
        } catch (Exception e) {
            android.util.Log.i("AdbHs", "ensureDaemons EXCEPTION: " + e);
        }
    }

    /** Kirim file base64 lewat shell ADB (tulis chunk lalu decode di proyektor). */
    private void pushB64(String remotePath, String b64, boolean exec) throws Exception {
        android.util.Log.i("AdbHs", "pushB64 " + remotePath + " (" + b64.length() + " chars)");
        adb.exec("rm -f " + remotePath + ".b64", 3000);
        final int CHUNK = 600;
        for (int i = 0; i < b64.length(); i += CHUNK) {
            String part = b64.substring(i, Math.min(i + CHUNK, b64.length()));
            adb.exec("echo " + part + " >> " + remotePath + ".b64", 5000);
        }
        adb.exec("base64 -d " + remotePath + ".b64 > " + remotePath
                + " && rm -f " + remotePath + ".b64", 10000);
        if (exec) adb.exec("chmod 755 " + remotePath, 3000);
        android.util.Log.i("AdbHs", "pushB64 " + remotePath + " SELESAI");
    }

    /** Kirim perintah ke daemon mouse virtual (non-blocking). */
    private void mouse(final String cmd) {
        if (!mouseConnected) return;
        final Socket s = mSock;
        final DataOutputStream o = mOut;
        net.execute(() -> {
            try { o.write((cmd + "\n").getBytes("UTF-8")); o.flush(); }
            catch (Exception e) { mouseConnected = false; }
        });
    }

    private void tcpReader() {
        try {
            byte[] buf = new byte[4096];
            while (connected) {
                int n = in.read(buf);
                if (n < 0) break;
            }
        } catch (Exception ignored) {}
        connected = false;
        ui.post(() -> { setStatus("Koneksi terputus", Color.RED); btnConnect.setText("Hubungkan"); });
    }

    /** Kirim perintah shell ke proyektor (dipanggil dari UI thread). */
    private void send(final String cmd) {
        if (!connected) { toast("Belum terhubung"); return; }
        net.execute(() -> {
            try {
                if (mode == Mode.ADB && adb != null) adb.send(cmd);
                else if (out != null) { out.write((cmd + "\n").getBytes("UTF-8")); out.flush(); }
            } catch (Exception e) {
                connected = false;
                ui.post(() -> setStatus("Gagal kirim: " + e.getMessage(), Color.RED));
            }
        });
    }

    // ================= helper UI =================

    private void setStatus(final String s, final int color) {
        ui.post(() -> { status.setText(s); status.setTextColor(color); });
    }

    private void toast(final String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    /** Klik kiri via touchscreen virtual (K = tap di posisi terakhir); fallback ENTER. */
    private void click() {
        if (mouseConnected) mouse("K");
        else send("input keyevent " + KC_ENTER);
    }

    /** Klik kanan via mouse virtual; fallback MENU. */
    private void tapRight() {
        if (mouseConnected) mouse("R");
        else send("input keyevent 82");
    }

    /** Tombol diagonal: kirim dua keyevent berurutan. */
    private Button mkDiag(String label, final int kc1, final int kc2) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setPadding(dp(6), dp(10), dp(6), dp(10));
        b.setMinimumHeight(dp(46));
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(v -> send("input keyevent " + kc1 + " " + kc2));
        return b;
    }

    private Button mkBtn(String label, int kc) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setPadding(dp(6), dp(10), dp(6), dp(10));
        b.setMinimumHeight(dp(46));
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(v -> send("input keyevent " + kc));
        attachRepeat(b, kc);
        return b;
    }

    /** Tahan tombol = repeat (buat D-pad & volume). */
    private void attachRepeat(final View v, final int kc) {
        final Handler h = new Handler(Looper.getMainLooper());
        final boolean[] holding = {false};
        final Runnable[] rep = new Runnable[1];
        rep[0] = () -> {
            if (!holding[0]) return;
            send("input keyevent " + kc);
            h.postDelayed(rep[0], 120);
        };
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    holding[0] = true;
                    h.postDelayed(rep[0], 500);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    holding[0] = false;
                    h.removeCallbacks(rep[0]);
                    break;
            }
            return false; // biar click biasa tetap jalan
        });
    }

    // ================= bangun UI =================

    /** Konversi dp → px supaya ukuran proporsional di semua density layar. */
    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Satu sel GridLayout — instance BARU tiap panggilan (jangan dibagi antar tombol!). */
    private GridLayout.LayoutParams gridCell() {
        GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
        gp.width = 0;
        gp.height = GridLayout.LayoutParams.WRAP_CONTENT;
        gp.setMargins(dp(3), dp(3), dp(3), dp(3));
        gp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
        gp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
        return gp;
    }

    /**
     * Section dengan header yang bisa diklik: ▾ lipat / ▸ buka isinya.
     * Mengembalikan container isi — tambahkan konten section ke situ.
     */
    private ViewGroup addCollapsible(LinearLayout root, String title, boolean expanded) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        final TextView head = new TextView(this);
        head.setText((expanded ? "▾ " : "▸ ") + title);
        head.setTextColor(Color.parseColor("#1565C0"));
        head.setTextSize(13);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setPadding(0, dp(14), 0, dp(6));
        head.setClickable(true);
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        head.setOnClickListener(v -> {
            boolean vis = body.getVisibility() == View.VISIBLE;
            body.setVisibility(vis ? View.GONE : View.VISIBLE);
            head.setText((vis ? "▸ " : "▾ ") + title);
        });
        wrap.addView(head);
        wrap.addView(body);
        root.addView(wrap);
        return body;
    }

    private View sectionLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.parseColor("#1565C0"));
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(14), 0, dp(6));
        return t;
    }

    // ================= bangun UI =================

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(Color.parseColor("#FAFAFA"));

        TextView title = new TextView(this);
        title.setText("TV Remote Proyektor");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#0D47A1"));
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(12);
        status.setPadding(0, dp(4), 0, dp(8));
        root.addView(status);

        // ---- mode ----
        root.addView(sectionLabel("Mode Koneksi"));
        rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        rg.setPadding(0, 0, 0, dp(2));
        rAdb = new RadioButton(this); rAdb.setText("ADB (5555)"); rAdb.setTextSize(13);
        rAdb.setMinimumHeight(dp(44));
        rTcp = new RadioButton(this); rTcp.setText("Service (9877)"); rTcp.setTextSize(13);
        rTcp.setMinimumHeight(dp(44));
        rg.addView(rAdb); rg.addView(rTcp);
        rg.check(rTcp.getId());          // pilih Service SETELAH tombol masuk grup
        root.addView(rg);
        rg.setOnCheckedChangeListener((g, id) -> {
            mode = (id == rAdb.getId()) ? Mode.ADB : Mode.TCP;
            // field port mengikuti mode, supaya tidak salah sambung
            etPort.setText(mode == Mode.ADB ? "5555" : "9877");
        });

        // ---- host/port ----
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, 0);
        etHost = new EditText(this); etHost.setHint("IP"); etHost.setInputType(InputType.TYPE_CLASS_TEXT);
        etHost.setSingleLine(true);
        etPort = new EditText(this); etPort.setHint("Port"); etPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        etPort.setSingleLine(true); etPort.setText("9877");
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp1.setMargins(0, 0, dp(6), 0);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f);
        etHost.setLayoutParams(lp1); etPort.setLayoutParams(lp2);
        row.addView(etHost); row.addView(etPort);
        root.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(6), 0, 0);
        btnConnect = new Button(this); btnConnect.setText("Hubungkan"); btnConnect.setAllCaps(false);
        Button btnScan = new Button(this); btnScan.setText("Scan"); btnScan.setAllCaps(false);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lb.setMargins(0, 0, dp(6), 0);
        btnConnect.setLayoutParams(lb);
        btnScan.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row2.addView(btnConnect); row2.addView(btnScan);
        root.addView(row2);

        btnConnect.setOnClickListener(v -> {
            if (connected) { net.execute(this::doDisconnect); setStatus("Terputus", Color.RED); btnConnect.setText("Hubungkan"); }
            else net.execute(this::doConnect);
        });
        btnScan.setOnClickListener(v -> net.execute(this::scan));

        // ---- touchpad mode mouse (geser = kursor, tap = klik) ----
        root.addView(sectionLabel("Touchpad (geser = kursor, tap = klik, tahan+geser = drag)"));
        View tp = new View(this) {
            float lx, ly; boolean moved, dragging; long downT;
            final Handler dragH = new Handler(Looper.getMainLooper());
            final Runnable dragStart = () -> { if (!moved && mouseConnected) { mouse("B 1"); dragging = true; } };

            /** Tinggi = 60% lebar (rasio sentuh ~16:10), di-clamp biar tetap nyaman. */
            @Override protected void onMeasure(int wSpec, int hSpec) {
                int w = MeasureSpec.getSize(wSpec);
                if (w <= 0) w = getResources().getDisplayMetrics().widthPixels - dp(32);
                int h = Math.round(w * 0.6f);
                h = Math.max(h, dp(170));
                h = Math.min(h, dp(360));
                setMeasuredDimension(w, h);
            }

            @Override public boolean onTouchEvent(MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        // kunci gerakan: jangan sampai ScrollView HP menyerap gesture vertikal
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        lx = e.getX(); ly = e.getY(); moved = false; dragging = false;
                        downT = System.currentTimeMillis();
                        dragH.postDelayed(dragStart, 600);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getX() - lx, dy = e.getY() - ly;
                        if (Math.abs(dx) >= 1 || Math.abs(dy) >= 1) {
                            if (mouseConnected) {
                                // mouse relatif: 1 px jari -> 2 px kursor
                                mouse("M " + (int) (dx * 2) + " " + (int) (dy * 2));
                            } else {
                                send("input swipe " + (int) lx + " " + (int) ly + " "
                                        + (int) e.getX() + " " + (int) e.getY() + " 40");
                            }
                            lx = e.getX(); ly = e.getY();
                            moved = true;
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        dragH.removeCallbacks(dragStart);
                        if (dragging) {
                            mouse("B 0");          // akhir drag: lepas tombol
                        } else if (!moved) {
                            if (mouseConnected) mouse("K");   // tap = klik di posisi kursor
                            else send("input keyevent " + KC_ENTER);
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        dragH.removeCallbacks(dragStart);
                        if (dragging) mouse("B 0");
                        break;
                }
                return true;
            }
        };
        // area touchpad: warna muda + garis tepi + sudut membulat
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(Color.parseColor("#E3F2FD"));
        gd.setStroke(dp(1), Color.parseColor("#BBDEFB"));
        gd.setCornerRadius(dp(12));
        tp.setBackground(gd);
        // tinggi PROPORSIONAL terhadap lebar (rasio ~16:10) — onMeasure di bawah,
        // bukan tinggi tetap px yang salah di layar density tinggi
        LinearLayout.LayoutParams tplp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tplp.topMargin = dp(6);
        tp.setLayoutParams(tplp);
        root.addView(tp);

        // tombol klik & scroll di bawah touchpad
        LinearLayout mrow = new LinearLayout(this);
        mrow.setOrientation(LinearLayout.HORIZONTAL);
        Button bClick = new Button(this); bClick.setText("Klik Kiri"); bClick.setAllCaps(false);
        Button bRight = new Button(this); bRight.setText("Klik Kanan"); bRight.setAllCaps(false);
        Button bUp2 = new Button(this); bUp2.setText("Scroll ▲"); bUp2.setAllCaps(false);
        Button bDn = new Button(this); bDn.setText("Scroll ▼"); bDn.setAllCaps(false);
        Button bHome2 = new Button(this); bHome2.setText("Kursor Home"); bHome2.setAllCaps(false);
        bClick.setOnClickListener(v -> click());
        bRight.setOnClickListener(v -> tapRight());
        bUp2.setOnClickListener(v -> { if (mouseConnected) mouse("S -1");
                                        else send("input swipe 640 500 640 250 200"); });
        bDn.setOnClickListener(v -> { if (mouseConnected) mouse("S 1");
                                       else send("input swipe 640 250 640 500 200"); });
        bHome2.setOnClickListener(v -> send("input keyevent 3"));
        for (Button b : new Button[]{bClick, bRight, bUp2, bDn, bHome2}) {
            b.setTextSize(12);
            b.setPadding(dp(2), dp(10), dp(2), dp(10));
            b.setMinimumHeight(dp(46));
            b.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            bl.setMargins(dp(3), 0, dp(3), 0);
            b.setLayoutParams(bl);
            mrow.addView(b);
        }
        mrow.setPadding(0, dp(6), 0, 0);
        root.addView(mrow);

        // ---- volume & media (paling sering dipakai, jangan disembunyikan) ----
        ViewGroup vmSec = addCollapsible(root, "Volume & Media", true);
        LinearLayout vmRow = new LinearLayout(this);
        vmRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] vmLbl = {"Vol −", "Vol +", "Mute", "⏮", "▶‖", "⏭"};
        int[] vmKey = {KC_VOLDOWN, KC_VOLUP, KC_MUTE, KC_PREV, KC_PLAYPAUSE, KC_NEXT};
        for (int i = 0; i < vmLbl.length; i++) {
            Button b = mkBtn(vmLbl[i], vmKey[i]);
            b.setTextSize(12);
            b.setPadding(dp(2), dp(10), dp(2), dp(10));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            bl.setMargins(dp(3), 0, dp(3), 0);
            b.setLayoutParams(bl);
            vmRow.addView(b);
        }
        vmSec.addView(vmRow);

        // ---- keyboard ----
        ViewGroup kbSec = addCollapsible(root, "Keyboard", true);
        etText = new EditText(this);
        etText.setHint("Ketik teks di sini lalu tekan Kirim");
        etText.setSingleLine(true);
        etText.setInputType(InputType.TYPE_CLASS_TEXT);
        kbSec.addView(etText);
        LinearLayout kb = new LinearLayout(this);
        kb.setOrientation(LinearLayout.HORIZONTAL);
        Button bSend = mkBtn("Kirim", -1); bSend.setOnClickListener(v -> {
            String t = etText.getText().toString();
            if (!t.isEmpty()) { send("input text '" + t.replace("'", "'\\''") + "'"); etText.setText(""); }
        });
        kb.addView(bSend);
        kb.addView(mkBtn("Enter", KC_ENTER));
        kb.addView(mkBtn("⌫", KC_SPACE)); // placeholder diganti bawah
        kb.addView(mkBtn("Tab", KC_TAB));
        kb.addView(mkBtn("Esc", KC_ESC));
        // width 0 + weight 1 → 5 kolom SAMA LEBAR semua muat (tanpa ini,
        // wrap_content bikin anak ke-5 (Esc) meluber keluar layar)
        for (int i = 0; i < kb.getChildCount(); i++) {
            LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            kl.setMargins(dp(3), dp(4), dp(3), 0);
            kb.getChildAt(i).setLayoutParams(kl);
        }
        kbSec.addView(kb);

        // tombol hapus teks (dedicated) — ikut section keyboard
        Button bDel = mkBtn("Hapus (⌫)", KC_SPACE);
        bDel.setText("Hapus (⌫)");
        // override: kirim keyevent 67 (DEL)
        bDel.setOnClickListener(v -> send("input keyevent 67"));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(0, dp(6), 0, 0);
        bDel.setLayoutParams(dlp);
        kbSec.addView(bDel);

        // ---- D-pad (8 arah: 4 utama + 4 diagonal) ----
        ViewGroup dpadSec = addCollapsible(root, "D-Pad (8 arah)", true);
        GridLayout dpad = new GridLayout(this);
        dpad.setColumnCount(3); dpad.setRowCount(3);

        // diagonal = kirim 2 keyevent berurutan (atas+kiri, dst)
        Button dUL = mkDiag("↖", KC_UP, KC_LEFT);
        Button dUR = mkDiag("↗", KC_UP, KC_RIGHT);
        Button dDL = mkDiag("↙", KC_DOWN, KC_LEFT);
        Button dDR = mkDiag("↘", KC_DOWN, KC_RIGHT);
        Button bUp = mkBtn("▲", KC_UP);
        Button bDown = mkBtn("▼", KC_DOWN);
        Button bLeft = mkBtn("◀", KC_LEFT);
        Button dpRight = mkBtn("▶", KC_RIGHT);
        Button bOk = mkBtn("OK", KC_CENTER);
        // instance LayoutParams SATU PER SATU — berbagi satu objek antar 9 tombol
        // bikin Grid salah hitung sel/ukuran (bug D-pad)
        for (Button b : new Button[]{dUL, bUp, dUR, bLeft, bOk, dpRight, dDL, bDown, dDR})
            b.setLayoutParams(gridCell());

        dpad.addView(dUL); dpad.addView(bUp); dpad.addView(dUR);
        dpad.addView(bLeft); dpad.addView(bOk); dpad.addView(dpRight);
        dpad.addView(dDL); dpad.addView(bDown); dpad.addView(dDR);
        dpadSec.addView(dpad);

        // ---- tombol lengkap (default CIUT — paling panjang; tap header utk buka) ----
        ViewGroup fullSec = addCollapsible(root, "Tombol Lengkap", false);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        String[][] btns = {
            {"Back", ""+KC_BACK}, {"Home", ""+KC_HOME}, {"Menu", ""+KC_MENU}, {"Power", ""+KC_POWER},
            {"Vol +", ""+KC_VOLUP}, {"Vol −", ""+KC_VOLDOWN}, {"Mute", ""+KC_MUTE}, {"Sleep", ""+KC_SLEEP},
            {"▶‖", ""+KC_PLAYPAUSE}, {"▶▶", ""+KC_FF}, {"◀◀", ""+KC_REW}, {"⏮", ""+KC_PREV},
            {"⏭", ""+KC_NEXT}, {"Ch +", ""+KC_CHUP}, {"Ch −", ""+KC_CHDOWN}, {"Input", ""+KC_INPUT},
            {"Search", ""+KC_SEARCH}, {"Guide", ""+KC_GUIDE}, {"Info", ""+KC_INFO}, {"Track", ""+KC_TRACK},
            {"PgUp", ""+KC_PAGEUP}, {"PgDn", ""+KC_PAGEDOWN}, {"Space", ""+KC_SPACE}, {"Wake", ""+KC_WAKEUP},
        };
        for (String[] bd : btns) {
            Button b = mkBtn(bd[0], Integer.parseInt(bd[1]));
            b.setLayoutParams(gridCell());
            grid.addView(b);
        }
        fullSec.addView(grid);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(root);
        setContentView(sv);
    }

    // ================= scan =================

    private void scan() {
        setStatus("Scan jaringan…", Color.GRAY);
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            // DhcpInfo tidak butuh izin lokasi (WifiInfo.getIpAddress diblokir sejak Android 10)
            int ip = wm.getDhcpInfo().ipAddress;
            if (ip == 0) { setStatus("Tidak terhubung WiFi", Color.RED); return; }
            String base = (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + ".";
            Set<String> found = new LinkedHashSet<>();
            List<Thread> ts = new ArrayList<>();
            for (int i = 1; i < 255; i++) {
                final String h = base + i;
                Thread t = new Thread(() -> {
                    for (int p : new int[]{5555, 9877}) {
                        try (Socket s = new Socket()) {
                            s.connect(new InetSocketAddress(h, p), 250);
                            found.add(h + ":" + p);
                        } catch (Exception ignored) {}
                    }
                });
                ts.add(t); t.start();
            }
            for (Thread t : ts) t.join(4000);
            ui.post(() -> {
                if (found.isEmpty()) setStatus("Scan: tidak ada device ditemukan", Color.RED);
                else {
                    // utamakan Service (9877); ADB (5555) hanya kalau 9877 tidak ada
                    String f = null;
                    for (String x : found) if (x.endsWith(":9877")) { f = x; break; }
                    if (f == null) f = found.iterator().next();
                    etHost.setText(f.split(":")[0]);
                    etPort.setText(f.split(":")[1]);
                    // simpan hasil scan juga, biar dibuka lagi langsung terisi
                    getSharedPreferences("cfg", MODE_PRIVATE).edit()
                            .putString("host", f.split(":")[0])
                            .putString("port", f.split(":")[1])
                            .apply();
                    // radio ikut tersinkron dengan mode hasil scan
                    if (f.endsWith(":5555")) rg.check(rAdb.getId()); else rg.check(rTcp.getId());
                    setStatus("Ditemukan: " + String.join(", ", found), Color.parseColor("#2E7D32"));
                }
            });
        } catch (Exception e) {
            setStatus("Scan error: " + e.getMessage(), Color.RED);
        }
    }

    private void detectLocalIp() {
        net.execute(() -> {
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                int ip = wm.getConnectionInfo().getIpAddress();
                String s = (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
                ui.post(() -> { if (etHost.getText().length() == 0) etHost.setHint("IP (mis. " + s + ")"); });
            } catch (Exception ignored) {}
        });
    }
}
