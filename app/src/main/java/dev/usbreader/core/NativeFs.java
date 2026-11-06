package dev.usbreader.core;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;

/** Serialized JNI ownership: no pointer is exposed to the UI; close is idempotent. */
public final class NativeFs implements Closeable {
    static { System.loadLibrary("usbreader"); }
    private long handle;
    private final BlockSource source;
    public NativeFs(BlockSource source) throws IOException {
        this.source = source;
        handle = open(source, source.size(), source.sectorSize());
        if (handle == 0) throw new IOException("无法打开磁盘");
    }
    private void checkOpen() throws IOException {
        if (handle == 0) throw new IOException("设备已关闭，请重新连接");
    }
    public synchronized Volume[] volumes() throws IOException { checkOpen(); return volumes(handle); }
    public synchronized Entry[] list(int volume, long inode) throws IOException {
        checkOpen();
        Entry[] entries = list(handle, volume, inode);
        Arrays.sort(entries, (a, b) -> a.directory != b.directory ? (a.directory ? -1 : 1)
                : a.name.compareToIgnoreCase(b.name));
        return entries;
    }
    public synchronized int read(int volume, long inode, long offset, byte[] buffer, int length) throws IOException {
        checkOpen();
        if (offset < 0 || length < 0 || length > buffer.length || length > 1024 * 1024)
            throw new IllegalArgumentException("Invalid read range");
        return read(handle, volume, inode, offset, buffer, length);
    }
    @Override public synchronized void close() throws IOException {
        if (handle != 0) { close(handle); handle = 0; source.close(); }
    }
    private static native long open(BlockSource source, long size, int sectorSize) throws IOException;
    private static native Volume[] volumes(long handle) throws IOException;
    private static native Entry[] list(long handle, int volume, long inode) throws IOException;
    private static native int read(long handle, int volume, long inode, long offset, byte[] data, int length) throws IOException;
    private static native void close(long handle);
}
