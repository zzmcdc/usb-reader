import dev.usbreader.core.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class NativeIntegrationTest {
    static final Path FIXTURES = Paths.get("tests/fixtures/generated");
    static int checks;
    static class FileSource implements BlockSource {
        final RandomAccessFile file; final long length; final int sector;
        boolean failReads;
        FileSource(Path path, int sector) throws Exception { file = new RandomAccessFile(path.toFile(), "r"); length = file.length(); this.sector = sector; }
        public long size() { return length; }
        public int sectorSize() { return sector; }
        public void read(long offset, byte[] bytes, int length) throws IOException {
            if (failReads) throw new IOException("injected USB disconnect");
            if (offset < 0 || offset > this.length - length) throw new IOException("out of range");
            file.seek(offset); file.readFully(bytes, 0, length);
        }
        public void close() throws IOException { file.close(); }
    }
    static void check(boolean result, String message) { checks++; if (!result) throw new AssertionError(message); }
    static Entry find(Entry[] rows, String name) { return Arrays.stream(rows).filter(e -> e.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError("Missing " + name + " in " + Arrays.toString(Arrays.stream(rows).map(e -> e.name).toArray()))); }
    static byte[] read(NativeFs fs, Volume v, Entry e) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[17003]; long offset = 0;
        while (offset < e.size) {
            int n = fs.read(v.index, e.inode, offset, buffer, (int) Math.min(e.size - offset, buffer.length));
            check(n > 0, "short read"); bytes.write(buffer, 0, n); offset += n;
        }
        check(fs.read(v.index, e.inode, e.size, buffer, 1) == 0, "EOF"); return bytes.toByteArray();
    }
    static void verify(String name, int sector, int expectedVolumes) throws Exception {
        try (NativeFs fs = new NativeFs(new FileSource(FIXTURES.resolve(name + ".img"), sector))) {
            Volume[] volumes = fs.volumes(); check(volumes.length == expectedVolumes, name + " partition count " + volumes.length);
            for (Volume v : volumes) {
                check(v.readable(), name + " " + v.error);
                Entry[] rows = fs.list(v.index, v.rootInode);
                Entry payload = find(rows, "payload.bin");
                check(Arrays.equals(read(fs, v, payload), Files.readAllBytes(FIXTURES.resolve("content/payload.bin"))), name + " byte-for-byte file mismatch");
                if (!name.equals("iso9660")) {
                    Entry unicode = find(rows, "中文😀.txt");
                    check(new String(read(fs, v, unicode), java.nio.charset.StandardCharsets.UTF_8).equals("你好，USB！\n"), name + " UTF-8 filename/content");
                }
                Optional<Entry> nested = Arrays.stream(rows).filter(e -> e.name.equals("nested")).findFirst();
                if (nested.isPresent()) check(new String(read(fs, v, find(fs.list(v.index, nested.get().inode), "deep.txt"))).equals("nested contents\n"), name + " subdirectory");
                if (v.type.equals("ext4")) {
                    Entry large = find(rows, "large.bin"); byte[] hole = new byte[64];
                    check(large.size == 5368709137L, "64-bit size");
                    check(fs.read(v.index, large.inode, 4294967313L, hole, 64) == 64, "64-bit offset");
                    check(Arrays.equals(hole, new byte[64]), "sparse hole must be zero");
                    check(Arrays.stream(rows).anyMatch(e -> e.name.equals("empty.txt") && e.size == 0), "zero-size file");
                }
                check(Arrays.stream(rows).noneMatch(e -> e.name.equals(".") || e.name.equals("..") || e.name.equals("$OrphanFiles")), "virtual entries hidden");
                System.out.println("PASS " + name + " / " + v.type + " / " + rows.length + " entries");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length>0) {for(String name:args)verify(name,512,1);System.out.println("Selected filesystems: "+checks+" checks passed");return;}
        for (String name : new String[] {"fat12", "fat16", "fat32", "exfat", "ext2", "ext3", "ext4", "ntfs", "iso9660"}) verify(name, 512, 1);
        verify("mbr", 512, 2); verify("gpt512", 512, 2); verify("gpt4096", 4096, 2);
        try (NativeFs fs = new NativeFs(new FileSource(FIXTURES.resolve("unknown.img"), 512))) {
            check(!fs.volumes()[0].readable(), "unknown filesystem must not be treated as readable");
        }
        try (NativeFs fs = new NativeFs(new FileSource(FIXTURES.resolve("truncated.img"), 512))) {
            Volume v = fs.volumes()[0];
            if (v.readable()) {
                try { fs.list(v.index, v.rootInode); throw new AssertionError("truncated filesystem returned data"); }
                catch (IOException expected) { checks++; }
            } else checks++;
        }
        FileSource broken = new FileSource(FIXTURES.resolve("fat32.img"), 512); broken.failReads = true;
        try (NativeFs fs = new NativeFs(broken)) {
            try { fs.volumes(); throw new AssertionError("USB error swallowed"); }
            catch (IOException e) { check(e.getMessage().contains("injected USB disconnect"), "original device exception must survive JNI"); }
        }
        NativeFs closed = new NativeFs(new FileSource(FIXTURES.resolve("fat32.img"), 512)); closed.close(); closed.close();
        try { closed.volumes(); throw new AssertionError("use after close"); } catch (IOException expected) { checks++; }
        System.out.println("Native integration: " + checks + " checks passed");
    }
}
