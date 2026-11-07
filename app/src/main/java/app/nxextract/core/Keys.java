package app.nxextract.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Chaves lidas de um prod.keys (formato "nome = hex", uma por linha). */
public final class Keys {
    private final Map<String, byte[]> keys = new HashMap<>();

    public static Keys parse(String text) {
        Keys k = new Keys();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue;
            int sep = line.indexOf('=');
            if (sep < 0) sep = line.indexOf(',');
            if (sep <= 0) continue;
            String name = line.substring(0, sep).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(sep + 1).trim().replace(" ", "");
            try {
                k.keys.put(name, Bin.unhex(value));
            } catch (IllegalArgumentException ignored) {
                // Linha que não é chave: ignora.
            }
        }
        return k;
    }

    public int count() {
        return keys.size();
    }

    public boolean has(String name) {
        return keys.containsKey(name);
    }

    byte[] require(String name, int length) throws NxException {
        byte[] v = keys.get(name);
        if (v == null) throw new NxException("Chave \"" + name + "\" não encontrada no prod.keys.");
        if (v.length != length) throw new NxException("Chave \"" + name + "\" tem tamanho errado (" + v.length + " bytes).");
        return v;
    }

    byte[] headerKey() throws NxException {
        return require("header_key", 32);
    }

    /** index: 0 aplicação, 1 ocean, 2 sistema. */
    byte[] keyAreaKey(int index, int revision) throws NxException {
        String[] kinds = {"application", "ocean", "system"};
        if (index < 0 || index >= kinds.length) throw new NxException("Índice de key area desconhecido: " + index);
        return require(String.format(Locale.ROOT, "key_area_key_%s_%02x", kinds[index], revision), 16);
    }

    byte[] titleKek(int revision) throws NxException {
        return require(String.format(Locale.ROOT, "titlekek_%02x", revision), 16);
    }
}
