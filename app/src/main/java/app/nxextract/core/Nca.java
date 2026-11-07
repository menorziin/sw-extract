package app.nxextract.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Arquivo NCA: cabeçalho cifrado em XTS e até 4 seções (normalmente AES-CTR). */
final class Nca {
    static final int TYPE_PROGRAM = 0;
    static final String[] TYPE_NAMES = {"Program", "Meta", "Control", "Manual", "Data", "PublicData"};

    static final int FS_ROMFS = 0;
    static final int FS_PFS0 = 1;

    final String name;
    final int contentType;
    final long programId;
    final int keyRevision;
    final List<Section> sections;

    final class Section {
        final int index;
        final long start;
        final long end;
        final int fsType;
        final int hashType;
        final int encryptionType;
        final String unsupported; // motivo, ou null
        private final byte[] fs;

        Section(int index, long start, long end, byte[] fs) {
            this.index = index;
            this.start = start;
            this.end = end;
            this.fs = fs;
            this.fsType = Bin.u8(fs, 2);
            this.hashType = Bin.u8(fs, 3);
            this.encryptionType = Bin.u8(fs, 4);
            this.unsupported = checkSupport();
        }

        private String checkSupport() {
            if (Bin.u64(fs, 0x108) != 0 || encryptionType == 4) {
                return "seção de atualização (BKTR): é preciso o jogo base junto";
            }
            if ("BKTR".equals(Bin.ascii(fs, 0x158, 4)) && Bin.u32(fs, 0x160) != 0) {
                return "seção esparsa (sparse), ainda não suportada";
            }
            if (Bin.u64(fs, 0x180) != 0) {
                return "seção comprimida (compression table), ainda não suportada";
            }
            if (encryptionType != 1 && encryptionType != 3 && encryptionType != 0) {
                return "tipo de criptografia " + encryptionType + " não suportado";
            }
            if (hashType != 2 && hashType != 3 && hashType != 5 && hashType != 6) {
                return "tipo de hash " + hashType + " não suportado";
            }
            return null;
        }

        /** Seção inteira, já decifrada. */
        Storage storage() throws IOException, NxException {
            if (unsupported != null) throw new NxException(name + " seção " + index + ": " + unsupported + ".");
            Storage base = new Storage.Sub(raw, start, end - start);
            if (encryptionType == 0 || encryptionType == 1) return base;
            byte[] ctrHigh = new byte[8];
            for (int i = 0; i < 8; i++) ctrHigh[i] = fs[0x147 - i];
            return new Crypto.CtrStorage(base, start, contentKey(), ctrHigh);
        }

        /** Dados úteis da seção: o PFS0 ou o RomFS, sem as tabelas de hash. */
        Storage data() throws IOException, NxException {
            Storage s = storage();
            long off;
            long size;
            if (hashType == 2 || hashType == 5) { // HierarchicalSha256: região 1
                off = Bin.u64(fs, 0x40);
                size = Bin.u64(fs, 0x48);
            } else { // HierarchicalIntegrity (IVFC): último nível
                if (!"IVFC".equals(Bin.ascii(fs, 0x08, 4))) throw new NxException(name + ": cabeçalho IVFC inválido.");
                off = Bin.u64(fs, 0x90);
                size = Bin.u64(fs, 0x98);
            }
            return new Storage.Sub(s, off, size);
        }
    }

    private final Storage raw;
    private final byte[] header;
    private final Keys keys;
    private final Map<String, Ticket> tickets;
    private byte[] contentKey;

    private Nca(String name, Storage raw, byte[] header, Keys keys, Map<String, Ticket> tickets) {
        this.name = name;
        this.raw = raw;
        this.header = header;
        this.keys = keys;
        this.tickets = tickets;
        this.contentType = Bin.u8(header, 0x205);
        this.programId = Bin.u64(header, 0x210);
        int rev = Math.max(Bin.u8(header, 0x206), Bin.u8(header, 0x220));
        this.keyRevision = rev > 0 ? rev - 1 : 0;

        List<Section> list = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long start = Bin.u32(header, 0x240 + i * 0x10) * 0x200;
            long end = Bin.u32(header, 0x244 + i * 0x10) * 0x200;
            if (start == 0 && end == 0) continue;
            byte[] fs = new byte[0x200];
            System.arraycopy(header, 0x400 + i * 0x200, fs, 0, 0x200);
            list.add(new Section(i, start, end, fs));
        }
        this.sections = Collections.unmodifiableList(list);
    }

    static Nca open(String name, Storage raw, Keys keys, Map<String, Ticket> tickets) throws IOException, NxException {
        if (raw.size() < 0xC00) throw new NxException(name + ": arquivo pequeno demais para um NCA.");
        byte[] enc = Bin.read(raw, 0, 0xC00);
        byte[] hdr = enc.clone();
        byte[] hk = keys.headerKey();
        Crypto.xts(hk, hdr, 0, 0xC00, 0, 0x200, true);
        String magic = Bin.ascii(hdr, 0x200, 4);
        if ("NCA2".equals(magic)) {
            // NCA2: cada cabeçalho de seção é cifrado como se fosse o setor 0.
            for (int i = 0; i < 4; i++) {
                System.arraycopy(enc, 0x400 + i * 0x200, hdr, 0x400 + i * 0x200, 0x200);
                Crypto.xts(hk, hdr, 0x400 + i * 0x200, 0x200, 0, 0x200, true);
            }
        } else if (!"NCA3".equals(magic)) {
            throw new NxException(name + ": não consegui decifrar o cabeçalho. O header_key do prod.keys está errado, "
                    + "ou o arquivo não é um NCA (NCZ comprimido?).");
        }
        long declared = Bin.u64(hdr, 0x208);
        if (declared > raw.size()) {
            throw new NxException(name + ": o NCA diz ter " + declared + " bytes mas só tem " + raw.size() + " (arquivo incompleto).");
        }
        return new Nca(name, raw, hdr, keys, tickets);
    }

    String typeName() {
        return contentType < TYPE_NAMES.length ? TYPE_NAMES[contentType] : "Tipo " + contentType;
    }

    String programIdHex() {
        return String.format("%016X", programId);
    }

    boolean hasRightsId() {
        return !Bin.allZero(header, 0x230, 16);
    }

    private synchronized byte[] contentKey() throws NxException {
        if (contentKey != null) return contentKey;
        if (hasRightsId()) {
            String rights = Bin.hex(header, 0x230, 16);
            Ticket t = tickets.get(rights);
            if (t == null) {
                throw new NxException(name + ": este jogo precisa do ticket (rights id " + rights
                        + "), que não está no arquivo. Use um dump com o .tik incluso.");
            }
            if (t.personalized) {
                throw new NxException(name + ": o ticket é personalizado (preso a um console) e não pode ser usado.");
            }
            contentKey = Crypto.ecbDecrypt(keys.titleKek(keyRevision), t.titleKeyBlock);
        } else {
            byte[] kak = keys.keyAreaKey(Bin.u8(header, 0x207), keyRevision);
            byte[] area = new byte[0x40];
            System.arraycopy(header, 0x300, area, 0, 0x40);
            byte[] dec = Crypto.ecbDecrypt(kak, area);
            contentKey = new byte[16];
            System.arraycopy(dec, 0x20, contentKey, 0, 16);
        }
        return contentKey;
    }
}
