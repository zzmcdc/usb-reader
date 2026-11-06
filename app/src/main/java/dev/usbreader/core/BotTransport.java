package dev.usbreader.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** USB Mass Storage Bulk-Only Transport. Only an explicit whitelist of read commands is sent. */
public final class BotTransport {
    private final BulkPipe pipe;
    private final int lun;
    private int tag;
    private int sectorSize;
    private long size;
    public BotTransport(BulkPipe pipe, int lun) {
        if (lun < 0 || lun > 15) throw new IllegalArgumentException("Invalid LUN");
        this.pipe = pipe; this.lun = lun;
    }
    public int sectorSize() { return sectorSize; }
    public long size() { return size; }

    public void initialize() throws IOException {
        // UNIT ATTENTION is common immediately after claiming a USB device.
        boolean ready = false;
        for (int attempt = 0; attempt < 5; attempt++) {
            try { command(new byte[6], new byte[0], 0); ready = true; break; }
            catch (ScsiException e) {
                byte[] sense = sense();
                int key = sense[2] & 15, asc = sense[12] & 255;
                if (key == 2 && asc == 0x3a) throw new IOException("读卡器中没有介质", e);
                if (key != 6 && key != 2) throw new IOException(describeSense(sense), e);
                try { Thread.sleep(150); } catch (InterruptedException x) {
                    Thread.currentThread().interrupt(); throw new IOException("已取消连接", x);
                }
            }
        }
        if (!ready) throw new IOException("U盘尚未就绪，请重新插入或检查供电");
        byte[] capacity = new byte[8];
        command(new byte[] {0x25,0,0,0,0,0,0,0,0,0}, capacity, 8);
        ByteBuffer b = ByteBuffer.wrap(capacity).order(ByteOrder.BIG_ENDIAN);
        long last = Integer.toUnsignedLong(b.getInt());
        long block = Integer.toUnsignedLong(b.getInt());
        if (last == 0xffffffffL) {
            byte[] cdb = new byte[16]; cdb[0] = (byte) 0x9e; cdb[1] = 0x10; cdb[13] = 32;
            capacity = new byte[32]; command(cdb, capacity, 32);
            b = ByteBuffer.wrap(capacity).order(ByteOrder.BIG_ENDIAN);
            last = b.getLong(); block = Integer.toUnsignedLong(b.getInt());
        }
        if (block < 512 || block > 65536 || (block & (block - 1)) != 0 || last < 0 || last == Long.MAX_VALUE)
            throw new IOException("设备返回了无效的磁盘容量/扇区大小");
        sectorSize = (int) block;
        try { size = Math.multiplyExact(last + 1, block); }
        catch (ArithmeticException e) { throw new IOException("磁盘容量超出支持范围", e); }
    }

    public synchronized void readBlocks(long lba, byte[] buffer, int length) throws IOException {
        if (sectorSize == 0 || length <= 0 || length > buffer.length || length > size || length % sectorSize != 0 ||
                lba < 0 || lba > (size - length) / sectorSize)
            throw new IOException("读取范围超出磁盘边界");
        int count = length / sectorSize;
        boolean use16 = lba > 0xffffffffL || lba + count - 1 > 0xffffffffL || count > 65535;
        byte[] cdb = new byte[use16 ? 16 : 10];
        ByteBuffer cmd = ByteBuffer.wrap(cdb).order(ByteOrder.BIG_ENDIAN);
        cdb[0] = use16 ? (byte) 0x88 : 0x28;
        if (use16) { cmd.putLong(2, lba); cmd.putInt(10, count); }
        else { cmd.putInt(2, (int) lba); cmd.putShort(7, (short) count); }
        for (int attempt = 0; ; attempt++) {
            try { command(cdb, buffer, length); return; }
            catch (ScsiException e) {
                byte[] sense = sense();
                if ((sense[2] & 15) == 6 && attempt == 0) continue;
                throw new IOException(describeSense(sense), e);
            }
        }
    }

    private byte[] sense() throws IOException {
        byte[] sense = new byte[18];
        command(new byte[] {3,0,0,0,18,0}, sense, sense.length);
        return sense;
    }
    private static String describeSense(byte[] s) {
        return String.format(java.util.Locale.ROOT, "磁盘读取失败 (SCSI sense %02x/%02x/%02x)", s[2] & 15, s[12] & 255, s[13] & 255);
    }
    private static final class ScsiException extends IOException {
        ScsiException() { super("SCSI command failed"); }
    }
    private void command(byte[] cdb, byte[] data, int length) throws IOException {
        int opcode = cdb[0] & 255;
        if (opcode != 0 && opcode != 3 && opcode != 0x25 && opcode != 0x9e && opcode != 0x28 && opcode != 0x88)
            throw new IOException("禁止发送非读取命令");
        int currentTag = ++tag;
        byte[] cbw = new byte[31];
        ByteBuffer b = ByteBuffer.wrap(cbw).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x43425355).putInt(currentTag).putInt(length).put((byte) 0x80).put((byte) lun).put((byte) cdb.length).put(cdb);
        try {
            if (pipe.transferOut(cbw, 0, 31) != 31) throw new IOException("USB 命令发送不完整");
            int received = 0;
            while (received < length) {
                // API 26/27 impose a 16 KiB bulk transfer limit.
                int request = Math.min(16384, length - received);
                int n = pipe.transferIn(data, received, request);
                if (n < 0) { pipe.clearInHalt(); break; }
                if (n > request) throw new IOException("USB 返回的数据长度无效");
                received += n;
                if (n < request) break; // short packet terminates the data stage
            }
            byte[] csw = new byte[13];
            int n = pipe.transferIn(csw, 0, 13);
            if (n < 0) { pipe.clearInHalt(); n = pipe.transferIn(csw, 0, 13); }
            if (n != 13) throw new IOException("USB 状态包不完整");
            b = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN);
            int signature = b.getInt(), returnedTag = b.getInt();
            long residue = Integer.toUnsignedLong(b.getInt());
            int status = csw[12] & 255;
            if (signature != 0x53425355 || returnedTag != currentTag || residue > length || status > 2)
                throw new IOException("USB 状态包校验失败");
            if (status == 2) throw new IOException("USB 传输阶段错误，请重新连接");
            if (status == 1) throw new ScsiException();
            if (received != length || residue != 0) throw new IOException("磁盘返回了不完整的数据");
        } catch (ScsiException e) { throw e; }
        catch (IOException e) {
            try { pipe.reset(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
            throw e;
        }
    }
}
