package dev.usbreader.usb;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import dev.usbreader.core.BlockSource;
import java.io.IOException;

/** Optional local raw image support, through the same native path used for a USB disk. */
public final class ImageDisk implements BlockSource {
    private final ParcelFileDescriptor descriptor;
    private final long size;
    private volatile boolean closed;
    public ImageDisk(ContentResolver resolver, Uri uri) throws IOException {
        descriptor = resolver.openFileDescriptor(uri, "r");
        if (descriptor == null) throw new IOException("无法打开镜像");
        try {
            size = Os.fstat(descriptor.getFileDescriptor()).st_size;
            if (size <= 0 || size % 512 != 0) throw new IOException("请选择本地、可随机访问、大小为 512 字节整数倍的原始磁盘镜像");
            Os.lseek(descriptor.getFileDescriptor(), 0, 0);
        } catch (Exception e) { descriptor.close(); throw new IOException("无法随机读取此镜像：" + e.getMessage(), e); }
    }
    @Override public long size() { return size; }
    @Override public int sectorSize() { return 512; }
    @Override public void read(long offset, byte[] target, int length) throws IOException {
        if (closed || offset < 0 || length < 0 || length > target.length || offset > size - length)
            throw new IOException("镜像已关闭或读取越界");
        int done = 0;
        try {
            while (done < length) {
                int n = Os.pread(descriptor.getFileDescriptor(), target, done, length - done, offset + done);
                if (n <= 0) throw new IOException("镜像数据不完整");
                done += n;
            }
        } catch (android.system.ErrnoException e) { throw new IOException(e); }
    }
    @Override public synchronized void close() throws IOException {
        if (!closed) { closed = true; descriptor.close(); }
    }
}
