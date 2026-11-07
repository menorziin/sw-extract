package app.nxextract.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Abre um NSP, XCI ou NCA e extrai o ExeFS e o RomFS dos NCAs de programa. */
public final class Extractor {
    public interface Listener {
        void log(String message);

        void progress(long done, long total, String current);

        boolean isCancelled();
    }

    public static final class Options {
        public boolean exefs = true;
        public boolean romfs = true;
    }

    public static final class Result {
        public final List<File> outputs = new ArrayList<>();
        public boolean cancelled;
    }

    private final Keys keys;
    private final Listener listener;
    private long done;
    private long total;

    public Extractor(Keys keys, Listener listener) {
        this.keys = keys;
        this.listener = listener;
    }

    private static final class NcaFile {
        final String name;
        final Storage storage;

        NcaFile(String name, Storage storage) {
            this.name = name;
            this.storage = storage;
        }
    }

    public Result extract(Storage rom, String romName, File outRoot, Options opt) throws IOException, NxException {
        Map<String, Ticket> tickets = new HashMap<>();
        List<NcaFile> ncaFiles = new ArrayList<>();
        List<String> compressed = new ArrayList<>();

        String lower = romName.toLowerCase(Locale.ROOT);
        if (PartitionFs.isPfs0(rom)) {
            listener.log("Formato: NSP (PFS0)");
            collect(PartitionFs.open(rom), rom, tickets, ncaFiles, compressed);
        } else if (rom.size() > 0x200 && "HEAD".equals(Bin.ascii(Bin.read(rom, 0x100, 4), 0, 4))) {
            listener.log("Formato: XCI");
            byte[] h = Bin.read(rom, 0x100, 0x100);
            long rootOff = Bin.u64(h, 0x30);
            long rootHeaderSize = Bin.u64(h, 0x38);
            if (rootOff <= 0 || rootOff >= rom.size()) throw new NxException("XCI com partição raiz inválida.");
            Storage rootStorage = new Storage.Sub(rom, rootOff, rom.size() - rootOff);
            PartitionFs root = PartitionFs.open(rootStorage);
            if (rootHeaderSize <= 0) listener.log("Aviso: tamanho do cabeçalho raiz zerado");
            PartitionFs.Entry secure = root.find("secure");
            if (secure == null) throw new NxException("XCI sem a partição \"secure\".");
            Storage secureStorage = new Storage.Sub(rootStorage, secure.offset, secure.size);
            collect(PartitionFs.open(secureStorage), secureStorage, tickets, ncaFiles, compressed);
        } else if (lower.endsWith(".nca")) {
            listener.log("Formato: NCA avulso");
            ncaFiles.add(new NcaFile(romName, rom));
        } else if (lower.endsWith(".nsz") || lower.endsWith(".xcz") || lower.endsWith(".ncz")) {
            throw new NxException("Arquivos NSZ/XCZ/NCZ são comprimidos. Descomprima no PC (nsz -D) antes.");
        } else {
            throw new NxException("Formato não reconhecido. Use NSP, XCI ou NCA.");
        }
        if (!tickets.isEmpty()) listener.log("Tickets: " + tickets.size());

        List<Nca> programs = new ArrayList<>();
        for (NcaFile f : ncaFiles) {
            Nca nca = Nca.open(f.name, f.storage, keys, tickets);
            listener.log(String.format(Locale.ROOT, "%s: %s, ID %s, geração de chave %d",
                    f.name, nca.typeName(), nca.programIdHex(), nca.keyRevision));
            if (nca.contentType == Nca.TYPE_PROGRAM) programs.add(nca);
        }
        if (programs.isEmpty()) {
            if (!compressed.isEmpty()) {
                throw new NxException("Os NCAs estão comprimidos (NCZ). Descomprima no PC (nsz -D) antes.");
            }
            throw new NxException("Nenhum NCA de programa encontrado (é DLC ou só atualização?).");
        }

        // Prepara as seções e soma o total para a barra de progresso.
        List<Job> jobs = new ArrayList<>();
        for (Nca nca : programs) jobs.add(plan(nca, opt));
        for (Job j : jobs) {
            if (j.exefs != null) total += j.exefsStorage.size();
            if (j.romfs != null) total += j.romfs.totalBytes;
        }
        boolean anything = false;
        for (Job j : jobs) anything |= j.exefs != null || j.romfs != null;
        if (!anything) {
            throw new NxException("Nada para extrair: nenhuma seção ExeFS/RomFS suportada.");
        }

        Result result = new Result();
        Set<String> usedDirs = new HashSet<>();
        for (Job j : jobs) {
            String dirName = j.nca.programIdHex();
            for (int n = 2; !usedDirs.add(dirName); n++) dirName = j.nca.programIdHex() + "_" + n;
            File out = new File(outRoot, dirName);
            if (j.exefs != null) {
                File dir = new File(out, "exefs");
                listener.log("Extraindo ExeFS para " + dir);
                if (!extractPfs(j.exefs, j.exefsStorage, dir)) {
                    result.cancelled = true;
                    return result;
                }
            }
            if (j.romfs != null) {
                File dir = new File(out, "romfs");
                listener.log(String.format(Locale.ROOT, "Extraindo RomFS (%d arquivos, %s) para %s",
                        j.romfs.fileCount, human(j.romfs.totalBytes), dir));
                boolean ok = j.romfs.extract(dir, new RomFs.Sink() {
                    String current = "";

                    @Override
                    public boolean copied(long bytes) {
                        done += bytes;
                        listener.progress(done, total, current);
                        return !listener.isCancelled();
                    }

                    @Override
                    public void file(String path, long size) {
                        current = "romfs/" + path;
                        listener.progress(done, total, current);
                    }
                });
                if (!ok) {
                    result.cancelled = true;
                    return result;
                }
            }
            result.outputs.add(out);
        }
        listener.progress(total, total, "");
        return result;
    }

    private static final class Job {
        final Nca nca;
        PartitionFs exefs;
        Storage exefsStorage;
        RomFs romfs;

        Job(Nca nca) {
            this.nca = nca;
        }
    }

    private Job plan(Nca nca, Options opt) throws IOException, NxException {
        Job job = new Job(nca);
        for (Nca.Section s : nca.sections) {
            if (s.unsupported != null) {
                listener.log("  seção " + s.index + " ignorada: " + s.unsupported);
                continue;
            }
            if (s.fsType == Nca.FS_PFS0 && opt.exefs && job.exefs == null) {
                Storage data = s.data();
                if (!PartitionFs.isPfs0(data)) {
                    throw new NxException(nca.name + ": a seção " + s.index + " não decifrou para PFS0. "
                            + "A chave de conteúdo está errada (prod.keys antigo para este jogo?).");
                }
                PartitionFs pfs = PartitionFs.open(data);
                if (pfs.find("main.npdm") != null) { // a outra seção PFS0 costuma ser o logo
                    job.exefs = pfs;
                    job.exefsStorage = data;
                }
            } else if (s.fsType == Nca.FS_ROMFS && opt.romfs && job.romfs == null) {
                try {
                    job.romfs = new RomFs(s.data());
                } catch (NxException e) {
                    throw new NxException(nca.name + ": " + e.getMessage());
                }
            }
        }
        if (opt.exefs && job.exefs == null) listener.log("  " + nca.name + ": sem ExeFS");
        if (opt.romfs && job.romfs == null) listener.log("  " + nca.name + ": sem RomFS");
        return job;
    }

    private boolean extractPfs(PartitionFs pfs, Storage data, File dir) throws IOException, NxException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Não consegui criar a pasta " + dir);
        byte[] buf = new byte[4 << 20];
        for (PartitionFs.Entry e : pfs.entries) {
            if (e.name.isEmpty() || e.name.contains("/") || e.name.equals("..")) {
                throw new NxException("Nome de arquivo suspeito no ExeFS: \"" + e.name + "\".");
            }
            listener.progress(done, total, "exefs/" + e.name);
            try (OutputStream os = new FileOutputStream(new File(dir, e.name))) {
                long copied = 0;
                while (copied < e.size) {
                    int n = (int) Math.min(buf.length, e.size - copied);
                    data.read(e.offset + copied, buf, 0, n);
                    os.write(buf, 0, n);
                    copied += n;
                    done += n;
                    listener.progress(done, total, "exefs/" + e.name);
                    if (listener.isCancelled()) return false;
                }
            }
        }
        return true;
    }

    private void collect(PartitionFs pfs, Storage base, Map<String, Ticket> tickets, List<NcaFile> ncas,
                         List<String> compressed) throws IOException, NxException {
        for (PartitionFs.Entry e : pfs.entries) {
            String n = e.name.toLowerCase(Locale.ROOT);
            if (n.endsWith(".tik")) {
                Ticket t = Ticket.parse(Bin.read(base, e.offset, Bin.checkedInt(e.size, e.name)));
                tickets.put(t.rightsId, t);
            } else if (n.endsWith(".nca")) {
                ncas.add(new NcaFile(e.name, new Storage.Sub(base, e.offset, e.size)));
            } else if (n.endsWith(".ncz")) {
                compressed.add(e.name);
            }
        }
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        do {
            v /= 1024;
            i++;
        } while (v >= 1024 && i < u.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", v, u[i]);
    }
}
