package app.nxextract.core;

/** Ticket (.tik) de um NSP: contém a title key cifrada de jogos com rights id. */
final class Ticket {
    final String rightsId; // hex, 32 caracteres
    final byte[] titleKeyBlock; // 16 bytes para tickets comuns
    final boolean personalized;

    private Ticket(String rightsId, byte[] titleKeyBlock, boolean personalized) {
        this.rightsId = rightsId;
        this.titleKeyBlock = titleKeyBlock;
        this.personalized = personalized;
    }

    static Ticket parse(byte[] tik) throws NxException {
        if (tik.length < 4) throw new NxException("Ticket vazio.");
        long sigType = Bin.u32(tik, 0);
        int sigSize;
        int padding;
        switch ((int) sigType) {
            case 0x10000: case 0x10003: sigSize = 0x200; padding = 0x3C; break; // RSA-4096
            case 0x10001: case 0x10004: sigSize = 0x100; padding = 0x3C; break; // RSA-2048
            case 0x10002: case 0x10005: sigSize = 0x3C; padding = 0x40; break;  // ECDSA
            default: throw new NxException(String.format("Tipo de assinatura de ticket desconhecido: 0x%x", sigType));
        }
        int data = 4 + sigSize + padding;
        if (tik.length < data + 0x180) throw new NxException("Ticket truncado.");
        byte[] block = new byte[16];
        System.arraycopy(tik, data + 0x40, block, 0, 16);
        boolean personalized = Bin.u8(tik, data + 0x141) != 0;
        String rights = Bin.hex(tik, data + 0x160, 16);
        return new Ticket(rights, block, personalized);
    }
}
