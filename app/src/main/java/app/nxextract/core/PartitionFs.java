package app.nxextract.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Sistemas de arquivos planos PFS0 (NSP, ExeFS) e HFS0 (partições de XCI). */
final class PartitionFs {
    static final class Entry {
        final String name;
        final long offset; // relativo ao início do Storage
        final long size;

        Entry(String name, long offset, long size) {
            this.name = name;
            this.offset = offset;
            this.size = size;
        }
    }

    final List<Entry> entries;

    private PartitionFs(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    static boolean isPfs0(Storage s) throws IOException {
        return s.size() >= 0x10 && "PFS0".equals(Bin.ascii(Bin.read(s, 0, 4), 0, 4));
    }

    static PartitionFs open(Storage s) throws IOException, NxException {
        if (s.size() < 0x10) throw new NxException("Partição pequena demais.");
        byte[] head = Bin.read(s, 0, 0x10);
        String magic = Bin.ascii(head, 0, 4);
        int entrySize;
        if ("PFS0".equals(magic)) entrySize = 0x18;
        else if ("HFS0".equals(magic)) entrySize = 0x40;
        else throw new NxException("Esperava PFS0/HFS0, encontrei \"" + magic.replaceAll("[^ -~]", "?") + "\".");

        long count = Bin.u32(head, 4);
        long stringsSize = Bin.u32(head, 8);
        long headerSize = 0x10 + count * entrySize + stringsSize;
        if (count > 100_000 || headerSize > s.size()) throw new NxException("Cabeçalho " + magic + " inválido.");
        byte[] header = Bin.read(s, 0, (int) headerSize);
        int strings = 0x10 + (int) count * entrySize;

        List<Entry> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int e = 0x10 + i * entrySize;
            long offset = Bin.u64(header, e);
            long size = Bin.u64(header, e + 8);
            long nameOff = Bin.u32(header, e + 16);
            if (nameOff >= stringsSize) throw new NxException("Nome inválido na entrada " + i + " de " + magic + ".");
            String name = Bin.cstring(header, strings + (int) nameOff, (int) (stringsSize - nameOff));
            long abs = headerSize + offset;
            if (offset < 0 || size < 0 || abs + size > s.size()) {
                throw new NxException("A entrada \"" + name + "\" passa do fim do arquivo (arquivo incompleto?).");
            }
            list.add(new Entry(name, abs, size));
        }
        return new PartitionFs(list);
    }

    Entry find(String name) {
        for (Entry e : entries) if (e.name.equals(name)) return e;
        return null;
    }
}
