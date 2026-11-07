package app.nxextract.core;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Leitura aleatória de um bloco de bytes (arquivo, partição ou seção decifrada). */
public interface Storage {
    long size();

    /** Lê exatamente {@code len} bytes a partir de {@code off}. */
    void read(long off, byte[] dst, int dstOff, int len) throws IOException;

    /** Arquivo aberto como FileChannel (no Android, vindo de um ParcelFileDescriptor). */
    final class FileStorage implements Storage {
        private final FileChannel channel;
        private final long size;

        public FileStorage(FileChannel channel) throws IOException {
            this.channel = channel;
            this.size = channel.size();
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public void read(long off, byte[] dst, int dstOff, int len) throws IOException {
            if (off < 0 || off + len > size) throw new EOFException("Leitura fora do arquivo em " + off);
            ByteBuffer buf = ByteBuffer.wrap(dst, dstOff, len);
            long pos = off;
            while (buf.hasRemaining()) {
                int n = channel.read(buf, pos);
                if (n < 0) throw new EOFException("Fim inesperado do arquivo em " + pos);
                pos += n;
            }
        }
    }

    /** Janela [offset, offset + size) de outro Storage. */
    final class Sub implements Storage {
        private final Storage base;
        private final long offset;
        private final long size;

        public Sub(Storage base, long offset, long size) throws IOException {
            if (offset < 0 || size < 0 || offset + size > base.size()) {
                throw new EOFException("Região fora dos limites: " + offset + "+" + size + " > " + base.size());
            }
            this.base = base;
            this.offset = offset;
            this.size = size;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public void read(long off, byte[] dst, int dstOff, int len) throws IOException {
            if (off < 0 || off + len > size) throw new EOFException("Leitura fora da região em " + off);
            base.read(offset + off, dst, dstOff, len);
        }
    }
}
