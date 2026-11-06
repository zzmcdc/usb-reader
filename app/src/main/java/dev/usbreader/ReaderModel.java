package dev.usbreader;

import android.content.Context;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import dev.usbreader.core.*;
import dev.usbreader.usb.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Survives configuration changes; all native and filesystem work runs on one worker. */
final class ReaderModel {
    interface Listener { void changed(); void progress(); }
    interface OpenSource { BlockSource open() throws Exception; }
    static final class Folder {
        final String name; final long inode;
        Folder(String name, long inode) { this.name = name; this.inode = inode; }
    }
    final Context context;
    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newSingleThreadExecutor();
    final AtomicInteger generation = new AtomicInteger();
    final AtomicBoolean cancelExport = new AtomicBoolean();
    Listener listener;
    boolean busy, exporting;
    String title = "未连接设备", status = "连接 U盘，开始浏览文件", message, preview;
    String deviceName;
    long capacity, progressBytes, progressTotal;
    Volume[] volumes = new Volume[0];
    Volume selected;
    Entry[] entries = new Entry[0];
    List<Folder> folders = new ArrayList<>();
    Entry pendingExport;
    int pendingVolume, pendingGeneration;
    UsbDisk.Target pendingTarget;
    int pendingLun;
    private volatile BlockSource source;
    private NativeFs fs; // owned exclusively by io

    ReaderModel(Context context) { this.context = context.getApplicationContext(); }
    void changed() { if (listener != null) listener.changed(); }
    void tell(String text) { message = text; changed(); }
    private void post(int g, Runnable runnable) { main.post(() -> { if (generation.get() == g) runnable.run(); }); }
    private void closeWorker() {
        try { if (fs != null) fs.close(); else if (source != null) source.close(); } catch (IOException ignored) {}
        fs = null; source = null;
    }
    void connect(UsbDisk.Target target, int lun) {
        open(target.name() + " · LUN " + lun, target.device.getDeviceName(),
                () -> new UsbDisk((UsbManager) context.getSystemService(Context.USB_SERVICE), target, lun));
    }
    void image(Uri uri, String name) { open(name, null, () -> new ImageDisk(context.getContentResolver(), uri)); }
    private void open(String name, String device, OpenSource factory) {
        if (busy) return;
        int g = generation.incrementAndGet();
        busy = true; exporting = false; title = name; deviceName = device; capacity = 0;
        volumes = new Volume[0]; selected = null; entries = new Entry[0]; folders.clear();
        status = "正在读取磁盘与分区…"; changed();
        io.execute(() -> {
            closeWorker();
            try {
                BlockSource opened = factory.open(); source = opened;
                if (generation.get() != g) { closeWorker(); return; }
                fs = new NativeFs(opened);
                Volume[] found = fs.volumes(); long bytes = opened.size();
                post(g, () -> {
                    busy = false; capacity = bytes; volumes = found;
                    long readable = Arrays.stream(found).filter(Volume::readable).count();
                    status = "发现 " + found.length + " 个分区 · " + readable + " 个可读取"; changed();
                });
            } catch (Exception | LinkageError e) {
                closeWorker(); post(g, () -> { busy = false; status = "连接失败"; message = error(e); changed(); });
            }
        });
    }
    void disconnect(String reason) {
        generation.incrementAndGet(); cancelExport.set(true);
        BlockSource current = source;
        if (current != null) { try { current.close(); } catch (IOException ignored) {} }
        io.execute(this::closeWorker);
        busy = false; exporting = false; title = "未连接设备"; deviceName = null; capacity = 0;
        volumes = new Volume[0]; entries = new Entry[0]; folders.clear(); selected = null;
        pendingTarget = null; pendingExport = null; preview = null; status = reason; changed();
    }
    void choose(Volume volume) {
        if (!volume.readable()) { tell("此分区暂时无法读取。可能是未支持、加密或损坏的文件系统。\n\n" + volume.error); return; }
        List<Folder> path = new ArrayList<>(); path.add(new Folder("根目录", volume.rootInode));
        list(volume, path);
    }
    void enter(Entry entry) {
        List<Folder> path = new ArrayList<>(folders); path.add(new Folder(entry.name, entry.inode)); list(selected, path);
    }
    void up() {
        if (busy) return;
        if (folders.size() > 1) list(selected, new ArrayList<>(folders.subList(0, folders.size() - 1)));
        else { selected = null; folders.clear(); entries = new Entry[0]; status = "选择要读取的分区"; changed(); }
    }
    void reload() { if (selected != null) list(selected, new ArrayList<>(folders)); }
    private void list(Volume volume, List<Folder> path) {
        if (busy || volume == null) return;
        int g = generation.get(); busy = true; status = "正在读取目录…"; changed();
        io.execute(() -> {
            try {
                Entry[] found = fs.list(volume.index, path.get(path.size() - 1).inode);
                post(g, () -> { busy = false; selected = volume; folders = path; entries = found;
                    status = found.length + " 个项目 · 点击文件可预览或导出"; changed(); });
            } catch (Exception e) { post(g, () -> { busy = false; status = "目录读取失败"; message = error(e); changed(); }); }
        });
    }
    void preview(Entry entry) {
        if (busy || selected == null) return;
        int g = generation.get(), v = selected.index; busy = true; status = "正在读取预览…"; changed();
        io.execute(() -> {
            try {
                int length = (int) Math.min(entry.size, 64 * 1024); byte[] bytes = new byte[length];
                int n = fs.read(v, entry.inode, 0, bytes, length);
                boolean binary = false;
                for (int i = 0; i < Math.min(n, 1024); i++) if (bytes[i] == 0) { binary = true; break; }
                String result;
                if (binary) {
                    StringBuilder hex = new StringBuilder("二进制预览（前 512 字节）\n\n");
                    for (int i = 0; i < Math.min(n, 512); i++) {
                        hex.append(String.format(Locale.ROOT, "%02X ", bytes[i] & 255));
                        if (i % 16 == 15) hex.append('\n');
                    }
                    result = hex.toString();
                } else result = new String(bytes, 0, n, StandardCharsets.UTF_8) + (entry.size > n ? "\n\n…预览仅显示前 64 KiB，请导出查看完整内容。" : "");
                String content = entry.name + "\n\n" + result;
                post(g, () -> { busy = false; preview = content; status = "预览读取完成"; changed(); });
            } catch (Exception e) { post(g, () -> { busy = false; status = "预览读取失败"; message = error(e); changed(); }); }
        });
    }
    void prepareExport(Entry entry) {
        pendingExport = entry; pendingVolume = selected.index; pendingGeneration = generation.get();
    }
    void export(Uri uri) {
        Entry entry = pendingExport; pendingExport = null;
        if (entry == null || pendingGeneration != generation.get() || busy) {
            tell("设备连接已经改变，请重新选择文件；刚创建的空目标文件可能需要手动删除。"); return;
        }
        int g = generation.get(), volume = pendingVolume;
        busy = true; exporting = true; cancelExport.set(false);
        progressBytes = 0; progressTotal = entry.size; status = "正在导出 " + entry.name; changed();
        io.execute(() -> {
            String failure = null; boolean complete = false;
            try (OutputStream output = context.getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException("无法创建目标文件");
                byte[] bytes = new byte[256 * 1024]; long offset = 0, lastUpdate = 0;
                while (offset < entry.size) {
                    if (cancelExport.get() || generation.get() != g) throw new IOException("导出已取消");
                    int n = fs.read(volume, entry.inode, offset, bytes, (int) Math.min(bytes.length, entry.size - offset));
                    if (n <= 0) throw new IOException("文件提前结束，未完成导出");
                    output.write(bytes, 0, n); offset += n;
                    long now = System.nanoTime();
                    if (now - lastUpdate > 200_000_000L || offset == entry.size) {
                        long done = offset; post(g, () -> { progressBytes = done; if (listener != null) listener.progress(); }); lastUpdate = now;
                    }
                }
                if (cancelExport.get() || generation.get() != g) throw new IOException("导出已取消");
                output.flush();
                // complete is set below only after OutputStream.close() also succeeds.
            } catch (Exception e) { failure = error(e); }
            if (failure == null) complete = true;
            boolean cleanupFailed = false;
            if (!complete) {
                try {
                    if (!DocumentsContract.deleteDocument(context.getContentResolver(), uri)) {
                        cleanupFailed = true;
                        failure += "\n目标位置可能留有未完成文件，请手动删除。";
                    }
                } catch (Exception e) { cleanupFailed = true; failure += "\n目标位置可能留有未完成文件，请手动删除。"; }
            }
            // Detach changes the generation; failed cleanup must still be reported.
            if (cleanupFailed && generation.get() != g)
                main.post(() -> tell("上次导出未完成，目标位置可能留有部分文件：" + entry.name + "。请手动删除。"));
            String outcome = complete ? "已导出：" + entry.name : failure;
            post(g, () -> { busy = false; exporting = false; status = outcome; message = outcome; changed(); });
        });
    }
    void dispose() { listener = null; disconnect("已关闭"); io.shutdown(); }
    static String error(Throwable e) { String msg = e.getMessage(); return msg == null ? e.getClass().getSimpleName() : msg; }
}
