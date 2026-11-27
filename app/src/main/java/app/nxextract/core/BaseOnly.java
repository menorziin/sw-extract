package app.nxextract.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tira o update de um NSP ou XCI: grava um NSP novo só com o jogo base (e, se pedido, as DLCs). Os NCAs são copiados como estão, sem decifrar nem
 * cifrar de novo; o prod.keys só serve para ler os metadados (.cnmt) e saber qual NCA é de quem. Só funciona quando o update está em NCAs separados
 * dentro do arquivo: um update já mesclado no base (NSPs do NSP Box, por exemplo) não tem mais os arquivos originais do base.
 */
public final class BaseOnly {
    static final int META_APPLICATION = 0x80;
    static final int META_PATCH = 0x81;
    static final int META_ADDON = 0x82;

    public static final class Result {
        public File output;
        public boolean cancelled;
    }

    private static final class Item {
        final String name;
        final Storage storage;

        Item(String name, Storage storage) {
            this.name = name;
            this.storage = storage;
        }
    }

    /** Um .cnmt: o título, a versão, o tipo e os IDs dos conteúdos que ele lista. */
    static final class Cnmt {
        final long titleId;
        final long version;
        final int type;
        final List<String> contentIds = new ArrayList<>();
        String metaNca;

        Cnmt(byte[] b) throws NxException {
            if (b.length < 0x20) throw new NxException(".cnmt pequeno demais.");
            titleId = Bin.u64(b, 0);
            version = Bin.u32(b, 8);
            type = Bin.u8(b, 0xC);
            int ext = Bin.u16(b, 0xE);
            int count = Bin.u16(b, 0x10);
            int at = 0x20 + ext;
            if (at + (long) count * 0x38 > b.length) throw new NxException(".cnmt com a lista de conteúdos cortada.");
            for (int i = 0; i < count; i++) contentIds.add(Bin.hex(b, at + i * 0x38 + 0x20, 16));
        }

        String typeName() {
            switch (type) {
                case META_APPLICATION: return "jogo base";
                case META_PATCH: return "update";
                case META_ADDON: return "DLC";
                default: return String.format(Locale.ROOT, "tipo 0x%02X", type);
            }
        }

        String describe() {
            return String.format(Locale.ROOT, "%s %016X, versão %d (v%d)", typeName(), titleId, version, version >> 16);
        }
    }

    private final Keys keys;
    private final Extractor.Listener listener;

    public BaseOnly(Keys keys, Extractor.Listener listener) {
        this.keys = keys;
        this.listener = listener;
    }

    public Result run(Storage rom, String romName, File outDir, boolean keepDlc) throws IOException, NxException {
        List<Item> items = new ArrayList<>();
        if (PartitionFs.isPfs0(rom)) {
            listener.log("Formato: NSP (PFS0)");
            PartitionFs pfs = PartitionFs.open(rom);
            for (PartitionFs.Entry e : pfs.entries) items.add(new Item(e.name, new Storage.Sub(rom, e.offset, e.size)));
        } else if (rom.size() > 0x200 && "HEAD".equals(Bin.ascii(Bin.read(rom, 0x100, 4), 0, 4))) {
            listener.log("Formato: XCI (o NSP gerado leva os NCAs da partição secure)");
            byte[] h = Bin.read(rom, 0x100, 0x100);
            long rootOff = Bin.u64(h, 0x30);
            if (rootOff <= 0 || rootOff >= rom.size()) throw new NxException("XCI com partição raiz inválida.");
            Storage root = new Storage.Sub(rom, rootOff, rom.size() - rootOff);
            PartitionFs.Entry secure = PartitionFs.open(root).find("secure");
            if (secure == null) throw new NxException("XCI sem a partição \"secure\".");
            Storage sec = new Storage.Sub(root, secure.offset, secure.size);
            for (PartitionFs.Entry e : PartitionFs.open(sec).entries) items.add(new Item(e.name, new Storage.Sub(sec, e.offset, e.size)));
        } else if (romName.toLowerCase(Locale.ROOT).matches(".*\\.(nsz|xcz)$")) {
            throw new NxException("Arquivos NSZ/XCZ são comprimidos. Descomprima no PC (nsz -D) antes.");
        } else {
            throw new NxException("Formato não reconhecido. Use NSP ou XCI.");
        }

        // Os tickets ficam para decifrar o .cnmt se ele tiver rights id (raro: os metadados quase sempre usam a chave do próprio NCA).
        Map<String, Ticket> tickets = new HashMap<>();
        for (Item it : items) {
            if (it.name.toLowerCase(Locale.ROOT).endsWith(".tik")) {
                Ticket t = Ticket.parse(Bin.read(it.storage, 0, Bin.checkedInt(it.storage.size(), it.name)));
                tickets.put(t.rightsId, t);
            }
        }

        List<Cnmt> metas = new ArrayList<>();
        for (Item it : items) {
            if (!it.name.toLowerCase(Locale.ROOT).endsWith(".cnmt.nca")) continue;
            Cnmt c = readCnmt(Nca.open(it.name, it.storage, keys, tickets));
            c.metaNca = it.name;
            metas.add(c);
            listener.log(it.name + ": " + c.describe() + ", " + c.contentIds.size() + " conteúdos");
        }
        if (metas.isEmpty()) throw new NxException("Nenhum metadado (.cnmt.nca) no arquivo: não dá para saber o que é base e o que é update.");

        List<Cnmt> keep = new ArrayList<>();
        int patches = 0;
        for (Cnmt c : metas) {
            if (c.type == META_APPLICATION || (keepDlc && c.type == META_ADDON)) keep.add(c);
            if (c.type == META_PATCH) patches++;
        }
        boolean hasBase = false;
        for (Cnmt c : keep) hasBase |= c.type == META_APPLICATION;
        if (!hasBase) throw new NxException("Este arquivo não tem o jogo base, só " + (patches > 0 ? "o update" : "DLC") + ".");
        if (patches == 0) {
            throw new NxException("Este arquivo não tem update separado: ou já é só o jogo base, ou o update foi mesclado no base "
                    + "(como nos NSPs do NSP Box). No segundo caso os arquivos originais do base não estão mais aqui e não dá para tirá-lo.");
        }

        // O que fica: o .cnmt.nca de cada título mantido, os conteúdos que ele lista (com .nca, .ncz ou .xml/.jpg de mesmo ID) e os tickets
        // e certificados desses títulos.
        Set<String> ids = new HashSet<>();
        Set<String> titles = new HashSet<>();
        for (Cnmt c : keep) {
            ids.add(stem(c.metaNca));
            for (String id : c.contentIds) ids.add(id.toLowerCase(Locale.ROOT));
            titles.add(String.format(Locale.ROOT, "%016x", c.titleId));
        }
        List<Item> out = new ArrayList<>();
        long total = 0;
        for (Item it : items) {
            String n = it.name.toLowerCase(Locale.ROOT);
            boolean take;
            if (n.endsWith(".tik") || n.endsWith(".cert")) take = n.length() >= 16 && titles.contains(n.substring(0, 16));
            else take = ids.contains(stem(n));
            listener.log((take ? "  mantém " : "  remove ") + it.name + " (" + Extractor.human(it.storage.size()) + ")");
            if (take) { out.add(it); total += it.storage.size(); }
        }
        for (Cnmt c : keep) {
            for (String id : c.contentIds) {
                boolean found = false;
                for (Item it : out) found |= stem(it.name).equals(id.toLowerCase(Locale.ROOT));
                if (!found) throw new NxException("O " + c.typeName() + " lista o conteúdo " + id + ", que não está no arquivo (dump incompleto?).");
            }
        }

        if (!outDir.isDirectory() && !outDir.mkdirs()) throw new IOException("Não consegui criar a pasta " + outDir);
        String base = romName.replaceAll("(?i)\\.(nsp|xci)$", "").replaceAll("[\\\\/:*?\"<>|]", "_");
        File dst = new File(outDir, base + " [base].nsp");
        listener.log("Gravando " + dst + " (" + Extractor.human(total) + ")");
        Result r = new Result();
        boolean ok = false;
        try {
            ok = writePfs0(out, dst, total);
        } finally {
            if (!ok && !dst.delete()) listener.log("Não consegui apagar o arquivo incompleto " + dst);
        }
        if (!ok) { r.cancelled = true; return r; }
        r.output = dst;
        return r;
    }

    private static String stem(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        int dot = n.indexOf('.');
        return dot < 0 ? n : n.substring(0, dot);
    }

    private static Cnmt readCnmt(Nca nca) throws IOException, NxException {
        for (Nca.Section s : nca.sections) {
            if (s.fsType != Nca.FS_PFS0 || s.unsupported != null) continue;
            Storage data = s.data();
            if (!PartitionFs.isPfs0(data)) {
                throw new NxException(nca.name + ": os metadados não decifraram. O prod.keys é mais antigo que este arquivo?");
            }
            for (PartitionFs.Entry e : PartitionFs.open(data).entries) {
                if (e.name.endsWith(".cnmt")) return new Cnmt(Bin.read(data, e.offset, Bin.checkedInt(e.size, e.name)));
            }
        }
        throw new NxException(nca.name + ": sem .cnmt dentro.");
    }

    /** Grava um PFS0 com os itens na ordem dada; false se foi cancelado. */
    private boolean writePfs0(List<Item> items, File dst, long total) throws IOException {
        byte[][] names = new byte[items.size()][];
        int strings = 0;
        for (int i = 0; i < items.size(); i++) {
            names[i] = items.get(i).name.getBytes(StandardCharsets.UTF_8);
            strings += names[i].length + 1;
        }
        int headerSize = 0x10 + items.size() * 0x18 + strings;
        int padded = (headerSize + 0x1F) & ~0x1F;   // como os outros geradores de NSP: o cabeçalho termina num múltiplo de 0x20
        strings += padded - headerSize;
        byte[] header = new byte[padded];
        put32(header, 0, 0x30534650);   // "PFS0"
        put32(header, 4, items.size());
        put32(header, 8, strings);
        int str = 0x10 + items.size() * 0x18;
        long off = 0;
        int nameOff = 0;
        for (int i = 0; i < items.size(); i++) {
            int e = 0x10 + i * 0x18;
            long size = items.get(i).storage.size();
            put64(header, e, off);
            put64(header, e + 8, size);
            put32(header, e + 16, nameOff);
            System.arraycopy(names[i], 0, header, str + nameOff, names[i].length);
            nameOff += names[i].length + 1;
            off += size;
        }
        byte[] buf = new byte[4 << 20];
        long done = 0;
        try (OutputStream os = new FileOutputStream(dst)) {
            os.write(header);
            for (Item it : items) {
                long size = it.storage.size();
                for (long at = 0; at < size; ) {
                    int n = (int) Math.min(buf.length, size - at);
                    it.storage.read(at, buf, 0, n);
                    os.write(buf, 0, n);
                    at += n;
                    done += n;
                    listener.progress(done, total, it.name);
                    if (listener.isCancelled()) return false;
                }
            }
        }
        return true;
    }

    private static void put32(byte[] b, int off, long v) {
        for (int i = 0; i < 4; i++) b[off + i] = (byte) (v >>> (8 * i));
    }

    private static void put64(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (8 * i));
    }
}
