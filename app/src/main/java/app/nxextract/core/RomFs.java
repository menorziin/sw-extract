package app.nxextract.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Extração de um RomFS (nível 3 do IVFC) para uma pasta. */
final class RomFs {
    private static final long NONE = 0xFFFFFFFFL;

    interface Sink {
        /** Chamado a cada pedaço copiado; devolve false para cancelar. */
        boolean copied(long bytes);

        void file(String path, long size);
    }

    private final Storage s;
    private final byte[] dirs;
    private final byte[] files;
    private final long dataOffset;
    int fileCount;
    long totalBytes;

    RomFs(Storage s) throws IOException, NxException {
        this.s = s;
        if (s.size() < 0x50) throw new NxException("RomFS pequeno demais.");
        byte[] h = Bin.read(s, 0, 0x50);
        if (Bin.u64(h, 0) != 0x50) throw new NxException("Cabeçalho de RomFS inválido (chave de conteúdo errada?).");
        long dirMetaOff = Bin.u64(h, 0x18);
        long dirMetaSize = Bin.u64(h, 0x20);
        long fileMetaOff = Bin.u64(h, 0x38);
        long fileMetaSize = Bin.u64(h, 0x40);
        dataOffset = Bin.u64(h, 0x48);
        dirs = Bin.read(s, dirMetaOff, Bin.checkedInt(dirMetaSize, "tabela de pastas do RomFS"));
        files = Bin.read(s, fileMetaOff, Bin.checkedInt(fileMetaSize, "tabela de arquivos do RomFS"));
        count(0, 0);
    }

    private void count(long dir, int depth) throws NxException {
        if (depth > 64) throw new NxException("RomFS com pastas aninhadas demais.");
        for (long f = Bin.u32(dirs, (int) dir + 12); f != NONE; f = Bin.u32(files, (int) f + 4)) {
            checkFile(f);
            fileCount++;
            totalBytes += Bin.u64(files, (int) f + 16);
        }
        for (long d = Bin.u32(dirs, (int) dir + 8); d != NONE; d = Bin.u32(dirs, (int) d + 4)) {
            checkDir(d);
            count(d, depth + 1);
        }
    }

    private void checkDir(long d) throws NxException {
        if (d < 0 || d + 0x18 > dirs.length) throw new NxException("Entrada de pasta inválida no RomFS.");
    }

    private void checkFile(long f) throws NxException {
        if (f < 0 || f + 0x20 > files.length) throw new NxException("Entrada de arquivo inválida no RomFS.");
    }

    private static String entryName(byte[] table, int nameLenOff, int nameOff) throws NxException {
        long len = Bin.u32(table, nameLenOff);
        if (len > 0x300 || nameOff + len > table.length) throw new NxException("Nome inválido no RomFS.");
        String name = new String(table, nameOff, (int) len, java.nio.charset.StandardCharsets.UTF_8);
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\0")) {
            throw new NxException("Nome de arquivo suspeito no RomFS: \"" + name + "\".");
        }
        return name;
    }

    /** Devolve false se foi cancelado. */
    boolean extract(File out, Sink sink) throws IOException, NxException {
        return extractDir(0, out, "", sink);
    }

    private boolean extractDir(long dir, File out, String prefix, Sink sink) throws IOException, NxException {
        if (!out.isDirectory() && !out.mkdirs()) throw new IOException("Não consegui criar a pasta " + out);
        byte[] buf = null;
        for (long f = Bin.u32(dirs, (int) dir + 12); f != NONE; f = Bin.u32(files, (int) f + 4)) {
            int fi = (int) f;
            String name = entryName(files, fi + 0x1C, fi + 0x20);
            long off = Bin.u64(files, fi + 8);
            long size = Bin.u64(files, fi + 16);
            sink.file(prefix + name, size);
            if (buf == null) buf = new byte[4 << 20];
            try (OutputStream os = new FileOutputStream(new File(out, name))) {
                long done = 0;
                while (done < size) {
                    int n = (int) Math.min(buf.length, size - done);
                    s.read(dataOffset + off + done, buf, 0, n);
                    os.write(buf, 0, n);
                    done += n;
                    if (!sink.copied(n)) return false;
                }
            }
        }
        for (long d = Bin.u32(dirs, (int) dir + 8); d != NONE; d = Bin.u32(dirs, (int) d + 4)) {
            int di = (int) d;
            String name = entryName(dirs, di + 0x14, di + 0x18);
            if (!extractDir(d, new File(out, name), prefix + name + "/", sink)) return false;
        }
        return true;
    }
}
