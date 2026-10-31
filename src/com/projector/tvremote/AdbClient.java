package com.projector.tvremote;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;

/**
 * Klien ADB protokol mentah (tanpa binary adb). Dipakai untuk mode "ADB over WiFi".
 * Kunci debug yang di-embed sudah di-authorized di perangkat, jadi tidak perlu pairing ulang.
 */
public class AdbClient {
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;
    private static final int MAXDATA = 256 * 1024;
    private static final String LOCAL_ADB_HOST = "127.0.0.1";
    private static final int LOCAL_ADB_PORT = 5037;

    private Socket sock;
    private DataInputStream in;
    private DataOutputStream out;
    private final Object wlock = new Object();

    private int localId;
    private int remoteId;
    private boolean shellOpen;
    private boolean viaServer;   // mode numpang server adb lokal (host:transport)
    private String serial;       // "ip:port" proyektor seperti terdaftar di server lokal
    private final StringBuilder shellBuf = new StringBuilder();

    private PrivateKey privKey;
    private byte[] pubKeyBlob; // format android_pubkey (520 byte) + comment

    public void setAuth(String privKeyPem, String pubKeyLine) throws Exception {
        String b64 = privKeyPem.replaceAll("-----[^-]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(b64);
        privKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        pubKeyBlob = (pubKeyLine.trim() + "\n").getBytes("UTF-8");
    }

    /**
     * Tanda tangan token AUTH ala adb: BUKAN SHA1(token), melainkan
     * DigestInfo(SHA1) + token MENTAH (token dianggap sebagai nilai hash).
     * Terverifikasi byte-identik dengan signature adb CLI (lihat HANDOFF 2.13).
     */
    private byte[] signToken(byte[] token) throws Exception {
        final byte[] sha1DigestInfo = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        };
        byte[] data = new byte[sha1DigestInfo.length + token.length];
        System.arraycopy(sha1DigestInfo, 0, data, 0, sha1DigestInfo.length);
        System.arraycopy(token, 0, data, sha1DigestInfo.length, token.length);
        try {
            // NONEwithRSA = PKCS#1 v1.5 atas data apa adanya (tanpa hashing)
            Signature s = Signature.getInstance("NONEwithRSA");
            s.initSign(privKey);
            s.update(data);
            return s.sign();
        } catch (Exception e) {
            // cadangan: enkripsi privat PKCS#1 v1.5 (block type 1) = signature
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(Cipher.ENCRYPT_MODE, privKey);
            return c.doFinal(data);
        }
    }

    public void connect(String host, int port) throws Exception {
        sock = new Socket();
        sock.connect(new InetSocketAddress(host, port), 6000);
        sock.setTcpNoDelay(true);
        // adbd proyektor bisa butuh >5 detik untuk membalas AUTH/pubkey, jadi beri
        // waktu lega; setelah shell terbuka timeout dimatikan (lihat bawah).
        sock.setSoTimeout(20000);
        in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()));

        writePacket(A_CNXN, 0x01000001, MAXDATA, "host::".getBytes("UTF-8"));
        android.util.Log.i("AdbHs", "tx CNXN -> " + host + ":" + port);

        boolean ok = false;
        boolean sentSig = false;   // signature hanya dicoba sekali; token kedua -> kirim publik
        try {
        for (int i = 0; i < 12 && !ok; i++) {
            Pkt p = readPacket();
            android.util.Log.i("AdbHs", "rx #" + i + " cmd=0x" + Integer.toHexString(p.cmd)
                    + " arg0=" + p.arg0 + " len=" + (p.data == null ? 0 : p.data.length));
            if (p.cmd == A_CNXN) { ok = true; break; }
            if (p.cmd == A_AUTH && p.arg0 == AUTH_TOKEN && privKey != null && !sentSig) {
                writePacket(A_AUTH, AUTH_SIGNATURE, 0, signToken(p.data));
                sentSig = true;
                android.util.Log.i("AdbHs", "tx AUTH SIGNATURE (#" + i + ")");
            } else if (p.cmd == A_AUTH && pubKeyBlob != null) {
                // signature ditolak -> proyektor minta identitas: kirim public key
                // (tanpa langkah ini handshake selalu gagal meski kunci sudah authorized)
                writePacket(A_AUTH, AUTH_RSAPUBLICKEY, 0, pubKeyBlob);
                android.util.Log.i("AdbHs", "tx AUTH PUBKEY (#" + i + ")");
            } else {
                android.util.Log.i("AdbHs", "packet tak dikenal -> break");
                break;
            }
        }
        } catch (Exception e) {
            android.util.Log.i("AdbHs", "EXCEPTION saat handshake: " + e);
            throw e;
        }
        if (!ok) { android.util.Log.i("AdbHs", "LOOP HABIS tanpa CNXN"); close(); throw new IOException("ADB handshake gagal (belum authorized?)"); }
        android.util.Log.i("AdbHs", "CNXN diterima — handshake sukses");

        localId = 1;
        String svc = "shell:";
        writePacket(A_OPEN, localId, 0, svc.getBytes("UTF-8"));
        for (int i = 0; i < 20; i++) {
            Pkt p = readPacket();
            if (p.arg1 != localId) continue;
            if (p.cmd == A_OKAY) { remoteId = p.arg0; shellOpen = true; break; }
            if (p.cmd == A_CLSE) break;
        }
        if (!shellOpen) { close(); throw new IOException("Gagal buka shell channel"); }
        try { sock.setSoTimeout(0); } catch (Exception ignored) {}   // shell interaktif menunggu lama
        new Thread(this::readerLoop, "adb-reader").start();
    }

    /**
     * Sambung dengan menumpang server adb lokal di HP (Termux, port 5037).
     * Server lokal yang menyimpan kunci ter-authorized — app TIDAK butuh kunci sama sekali:
     *   1) host:connect:<ip>:5555  -> server lokal yang autentik ke proyektor
     *   2) host:transport:<ip:port> -> socket jadi pipa mentah ke transport itu
     *   3) "shell:"                 -> shell interaktif (protokol shell v1 legacy, stdin/stdout mentah)
     * Cara ini yang dipakai adb CLI di Termux — jadi selama adb server jalan, app pasti bisa.
     */
    public void connectViaLocalServer(String host, int port) throws Exception {
        this.serial = host + ":" + port;
        this.viaServer = true;
        // pastikan server lokal hidup + transport ke proyektor tersedia
        Socket c = new Socket();
        try {
            c.connect(new InetSocketAddress(LOCAL_ADB_HOST, LOCAL_ADB_PORT), 3000);
            c.setSoTimeout(5000);
            DataOutputStream co = new DataOutputStream(c.getOutputStream());
            DataInputStream ci = new DataInputStream(c.getInputStream());
            String rep = srvExchange(co, ci, "host:connect:" + serial);
            android.util.Log.i("AdbHs", "host:connect:" + serial + " -> " + rep);
        } catch (IOException e) {
            throw new IOException("server adb lokal tidak jalan di 127.0.0.1:5037"
                    + " — jalankan adb dulu di Termux (" + e.getMessage() + ")");
        } finally {
            try { c.close(); } catch (Exception ignored) {}
        }
        // host:connect bersifat asinkron — coba buka shell sampai transport siap
        Exception last = null;
        for (int i = 0; i < 8; i++) {
            try {
                openServerShell();
                last = null;
                break;
            } catch (IOException e) {
                last = e;
                android.util.Log.i("AdbHs", "openServerShell #" + i + " gagal: " + e.getMessage());
                Thread.sleep(600);
            }
        }
        if (last != null) throw last;
        android.util.Log.i("AdbHs", "shell via server lokal SIAP (" + serial + ")");
        new Thread(this::serverReaderLoop, "adb-server-reader").start();
    }

    /** Buka sesi: host:transport + shell: interaktif lewat server adb lokal. */
    private void openServerShell() throws IOException {
        sock = new Socket();
        sock.connect(new InetSocketAddress(LOCAL_ADB_HOST, LOCAL_ADB_PORT), 3000);
        sock.setSoTimeout(8000);
        sock.setTcpNoDelay(true);
        in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()));
        String rep = srvExchange(out, in, "host:transport:" + serial);
        if (!rep.startsWith("OKAY")) { close(); throw new IOException("host:transport: " + rep); }
        rep = srvExchange(out, in, "shell:");
        if (!rep.startsWith("OKAY")) { close(); throw new IOException("shell: " + rep); }
        try { sock.setSoTimeout(0); } catch (Exception ignored) {}
        shellOpen = true;
        // JANGAN kirim "stty -echo" — di proyektor terbukti justru memicu translasi
        // huruf besar→kecil pada semua input (empiris: echo CASE → case). Noise baris
        // echo ditangani oleh parsing exec() yang membuang baris teks perintah.
        try {
            Thread.sleep(200);                 // telan prompt/login awal
            synchronized (shellBuf) { shellBuf.setLength(0); }
        } catch (Exception ignored) {}
    }

    /** Protokol query server adb: 4-hex panjang + string; balasan "OKAY" atau "FAIL"+len+pesan. */
    private static String srvExchange(DataOutputStream o, DataInputStream i, String q) throws IOException {
        byte[] b = q.getBytes("UTF-8");
        synchronized (o) {
            o.write(String.format("%04x", b.length).getBytes("UTF-8"));
            o.write(b);
            o.flush();
        }
        // server kadang menyelipkan frame informasi (mis. "already connected to ...")
        // sebelum OKAY/FAIL — telan dulu sampai dapat respons sungguhan
        while (true) {
            byte[] h = new byte[4];
            i.readFully(h);
            String s = new String(h, "UTF-8");
            if (s.equals("FAIL")) {
                byte[] ll = new byte[4];
                i.readFully(ll);
                int ln = Integer.parseInt(new String(ll, "UTF-8"), 16);
                byte[] msg = new byte[Math.min(ln, 512)];
                i.readFully(msg);
                return "FAIL " + new String(msg, "UTF-8");
            }
            if (s.equals("OKAY")) return "OKAY";
            // bukan OKAY/FAIL → asumsikan 4-hex panjang frame info
            int infoLen = Integer.parseInt(s, 16);
            byte[] info = new byte[Math.min(infoLen, 4096)];
            i.readFully(info);
        }
    }

    /** Pompa byte mentah dari shell (mode server) ke shellBuf. */
    private void serverReaderLoop() {
        try {
            byte[] buf = new byte[4096];
            while (shellOpen) {
                int n = in.read(buf);
                if (n < 0) break;
                synchronized (shellBuf) {
                    shellBuf.append(new String(buf, 0, n, "UTF-8"));
                    shellBuf.notifyAll();
                }
            }
        } catch (Exception ignored) {}
        shellOpen = false;
    }

    private void readerLoop() {
        try {
            while (shellOpen) {
                Pkt p = readPacket();
                if (p.arg1 != localId) continue;
                if (p.cmd == A_WRTE) {
                    synchronized (shellBuf) {
                        shellBuf.append(new String(p.data, "UTF-8"));
                        shellBuf.notifyAll();
                    }
                    writePacket(A_OKAY, localId, remoteId, null);
                } else if (p.cmd == A_CLSE) {
                    shellOpen = false;
                }
            }
        } catch (Exception ignored) { shellOpen = false; }
    }

    public boolean isConnected() { return shellOpen && sock != null && !sock.isClosed(); }

    /** Kirim perintah shell tanpa menunggu balasan (untuk tombol, sentuh, ketik). */
    public void send(String cmd) throws IOException {
        if (!isConnected()) throw new IOException("Belum terhubung");
        byte[] d = (cmd + "\n").getBytes("UTF-8");
        synchronized (wlock) {
            if (viaServer) {
                out.write(d);          // shell v1: tulis stdin mentah langsung ke socket
                out.flush();
            } else {
                writePacket(A_WRTE, localId, remoteId, d);
            }
        }
    }

    /**
     * Jalankan perintah dan tunggu output sampai timeout (untuk cek status).
     * Marker dibuat lewat printf: teks perintah yang ikut ter-echo di baris terminal
     * hanya berisi '__R%s__' (bukan marker lengkap), jadi baris echo tidak pernah
     * disalahartikan sebagai balasan. Pakai printf, BUKAN $((...)) — shell proyektor
     * aritmatikanya 32-bit (ARM32), token nanoTime bisa ter-truncate.
     */
    public String exec(String cmd, int timeoutMs) throws Exception {
        String token = Long.toString(System.nanoTime());
        String marker = "__R" + token + "__";
        synchronized (shellBuf) { shellBuf.setLength(0); }
        send(cmd + "; printf '__R%s__\\n' " + token);
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (shellBuf) {
            while (System.currentTimeMillis() < deadline) {
                int idx = shellBuf.indexOf(marker);
                if (idx >= 0) {
                    String r = shellBuf.substring(0, idx);
                    // kalau echo terminal masih aktif, teks perintah ikut terbaca —
                    // buang sampai akhir baris yang memuat teks perintah
                    int ci = r.indexOf(cmd);
                    if (ci >= 0) {
                        int nl = r.indexOf('\n', ci);
                        r = (nl >= 0) ? r.substring(nl + 1) : "";
                    }
                    r = r.trim();
                    shellBuf.delete(0, idx + marker.length());
                    // telan prompt yang menyusul supaya tidak bocor ke exec berikutnya
                    try { shellBuf.wait(60); } catch (InterruptedException ignored) {}
                    shellBuf.delete(0, shellBuf.length());
                    return r;
                }
                shellBuf.wait(Math.min(300, Math.max(1, deadline - System.currentTimeMillis())));
            }
        }
        String peek;
        synchronized (shellBuf) {
            peek = shellBuf.length() > 200 ? shellBuf.substring(0, 200) : shellBuf.toString();
        }
        android.util.Log.i("AdbHs", "exec TIMEOUT (" + timeoutMs + "ms): " + cmd + " | buf=" + peek);
        throw new IOException("Timeout menunggu balasan: " + cmd);
    }

    public void close() {
        shellOpen = false;
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
    }

    // ---------- framing ADB ----------

    private static class Pkt {
        int cmd, arg0, arg1; byte[] data;
    }

    private void writePacket(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        if (data == null) data = new byte[0];
        // protokol adb: field checksum = JUMLAH byte (bukan CRC32!) — kalau salah,
        // adbd diam-diam membuang packet (AUTH signature/pubkey tak pernah diproses)
        int sum = 0;
        for (byte x : data) sum += (x & 0xff);
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length);
        b.putInt(sum);
        b.putInt(cmd ^ 0xffffffff);
        b.put(data);
        synchronized (wlock) {
            out.write(b.array());
            out.flush();
        }
    }

    private Pkt readPacket() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Pkt p = new Pkt();
        p.cmd = b.getInt(); p.arg0 = b.getInt(); p.arg1 = b.getInt();
        int len = b.getInt();
        b.getInt(); b.getInt(); // data_check & magic
        if (len < 0 || len > MAXDATA + 1024) throw new IOException("Payload ADB tidak wajar: " + len);
        p.data = new byte[len];
        if (len > 0) in.readFully(p.data);
        return p;
    }
}
