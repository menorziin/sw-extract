package app.nxextract.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Leitura de inteiros little-endian e utilidades de bytes. */
final class Bin {
    private Bin() {}

    static int u8(byte[] b, int off) {
        return b[off] & 0xff;
    }

    static int u16(byte[] b, int off) {
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8;
    }

    static long u32(byte[] b, int off) {
        return (b[off] & 0xffL) | (b[off + 1] & 0xffL) << 8 | (b[off + 2] & 0xffL) << 16
                | (b[off + 3] & 0xffL) << 24;
    }

    static long u64(byte[] b, int off) {
        return u32(b, off) | u32(b, off + 4) << 32;
    }

    static String ascii(byte[] b, int off, int len) {
        return new String(b, off, len, StandardCharsets.US_ASCII);
    }

    /** String terminada em zero, em UTF-8. */
    static String cstring(byte[] b, int off, int max) {
        int end = off;
        int limit = Math.min(b.length, off + max);
        while (end < limit && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    static boolean allZero(byte[] b, int off, int len) {
        for (int i = 0; i < len; i++) if (b[off + i] != 0) return false;
        return true;
    }

    static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) sb.append(String.format("%02x", b[off + i] & 0xff));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        if ((s.length() & 1) != 0) throw new IllegalArgumentException("hex de tamanho ímpar");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16);
            int lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("caractere hex inválido");
            out[i] = (byte) (hi << 4 | lo);
        }
        return out;
    }

    static byte[] read(Storage s, long off, int len) throws IOException {
        byte[] b = new byte[len];
        s.read(off, b, 0, len);
        return b;
    }

    static int checkedInt(long v, String what) throws NxException {
        if (v < 0 || v > Integer.MAX_VALUE) throw new NxException("Tamanho inválido em " + what + ": " + v);
        return (int) v;
    }
}
