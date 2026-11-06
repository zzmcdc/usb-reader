import dev.usbreader.core.*;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Deterministic BOT device emulator: validates actual CBW/CDB bytes and injects protocol failures. */
public final class BotTransportTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    interface Checked { void run() throws Exception; }
    static void expectError(Checked action, String name) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + name); }
        catch (IOException expected) { checks++; }
    }
    static final class Device implements BulkPipe {
        final int sector; final long lastBlock;
        byte[] data, csw; int position, resets, halts;
        int readyFailures, senseKey = 6, asc = 0x29;
        boolean wrongTag, shortRead, stallStatus, stallData, phaseError, closed, mediumError;
        long requestedLba; int readOpcode; int expectedLun;
        Device(int sector, long last) { this.sector = sector; lastBlock = last; }
        @Override public int transferOut(byte[] bytes, int offset, int length) throws IOException {
            if (closed) throw new IOException("detached");
            check(length == 31 && offset == 0, "CBW has 31 bytes");
            ByteBuffer cbw = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            check(cbw.getInt() == 0x43425355, "CBW signature");
            int tag = cbw.getInt(), size = cbw.getInt();
            check((cbw.get() & 255) == 0x80, "read-only USB direction");
            check((cbw.get() & 255) == expectedLun, "LUN"); int cdbLength = cbw.get() & 255;
            byte[] cdb = Arrays.copyOfRange(bytes, 15, 15+cdbLength); int opcode = cdb[0] & 255;
            check(Set.of(0, 3, 0x25, 0x9e, 0x28, 0x88).contains(opcode), "write commands forbidden");
            data = new byte[size]; position = 0; int status = 0;
            ByteBuffer be = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            if (opcode == 0 && readyFailures-- > 0) status = 1;
            else if (opcode == 3) { data[0] = 0x70; data[2] = (byte)senseKey; data[12] = (byte)asc; }
            else if (opcode == 0x25) { be.putInt((int)Math.min(lastBlock, 0xffffffffL)).putInt(sector); }
            else if (opcode == 0x9e) {
                check(cdb.length == 16 && cdb[1] == 0x10 && cdb[13] == 32, "READ CAPACITY16 allocation length");
                be.putLong(lastBlock).putInt(sector);
            } else if (opcode == 0x28 || opcode == 0x88) {
                readOpcode = opcode; ByteBuffer cmd = ByteBuffer.wrap(cdb).order(ByteOrder.BIG_ENDIAN);
                requestedLba = opcode == 0x28 ? Integer.toUnsignedLong(cmd.getInt(2)) : cmd.getLong(2);
                long count = opcode == 0x28 ? Short.toUnsignedInt(cmd.getShort(7)) : Integer.toUnsignedLong(cmd.getInt(10));
                check(count * sector == size, "CDB block count");
                for (int i = 0; i < size; i++) data[i] = (byte)(requestedLba + i);
                if (mediumError) status = 1;
            }
            csw = new byte[13]; ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(0x53425355).putInt(tag + (wrongTag ? 1 : 0)).putInt(shortRead ? sector : 0).put((byte)(phaseError ? 2 : status));
            return 31;
        }
        @Override public int transferIn(byte[] bytes, int offset, int length) throws IOException {
            if (closed) throw new IOException("detached");
            check(length <= 16384, "Android 26 bulk limit");
            if (position < data.length) {
                if (stallData) { stallData = false; position = data.length; return -1; }
                int count = Math.min(length, data.length - position);
                if (shortRead) count = Math.max(0, count - sector);
                System.arraycopy(data, position, bytes, offset, count); position += count;
                if (shortRead) position = data.length;
                return count;
            }
            if (stallStatus) { stallStatus = false; return -1; }
            check(length == 13, "CSW request"); System.arraycopy(csw, 0, bytes, offset, 13); return 13;
        }
        public void clearInHalt() { halts++; }
        public void reset() { resets++; }
        public void close() { closed = true; }
    }
    public static void main(String[] args) throws Exception {
        Device normal = new Device(512, 262143); normal.expectedLun = 3;
        BotTransport transport = new BotTransport(normal, 3); transport.initialize();
        check(transport.size() == 128*1024*1024 && transport.sectorSize() == 512, "capacity10");
        byte[] data = new byte[128*1024]; transport.readBlocks(1234, data, data.length);
        check(normal.requestedLba == 1234 && normal.readOpcode == 0x28, "READ10 addressing");
        for (int i = 0; i < data.length; i++) if (data[i] != (byte)(1234+i)) throw new AssertionError("payload mismatch");
        expectError(() -> transport.readBlocks(-1, data, 512), "negative address");
        expectError(() -> transport.readBlocks(262144, data, 512), "end-of-disk");
        expectError(() -> transport.readBlocks(0, data, 513), "unaligned read");
        Device large = new Device(4096, 0x100000123L); BotTransport t16 = new BotTransport(large, 0); t16.initialize();
        check(t16.size() == (0x100000123L+1)*4096, "capacity16 is 64-bit");
        t16.readBlocks(0x100000001L, new byte[4096], 4096);
        check(large.readOpcode == 0x88 && large.requestedLba == 0x100000001L, "READ16 high address");
        t16.readBlocks(0xffffffffL, new byte[8192], 8192); check(large.readOpcode == 0x88, "READ16 boundary crossing");
        Device attention = new Device(512, 32767); attention.readyFailures = 1;
        new BotTransport(attention, 0).initialize(); check(attention.resets == 0, "unit attention handled using request sense");
        Device absent = new Device(512, 32767); absent.readyFailures = 9; absent.senseKey = 2; absent.asc = 0x3a;
        expectError(() -> new BotTransport(absent, 0).initialize(), "missing medium");
        normal.wrongTag = true; expectError(() -> transport.readBlocks(1, data, 512), "CSW tag validation"); check(normal.resets == 1, "reset recovery"); normal.wrongTag = false;
        normal.shortRead = true; expectError(() -> transport.readBlocks(1, data, 512), "short read"); normal.shortRead = false;
        normal.stallStatus = true; transport.readBlocks(1, data, 512); check(normal.halts == 1, "CSW stall retry");
        normal.stallData = true; expectError(() -> transport.readBlocks(1, data, 512), "data stall"); check(normal.halts == 2, "data stall cleared");
        normal.phaseError = true; expectError(() -> transport.readBlocks(1, data, 512), "phase error"); normal.phaseError = false;
        normal.mediumError = true; normal.senseKey = 3; normal.asc = 0x11;
        expectError(() -> transport.readBlocks(1, data, 512), "medium error with sense"); normal.mediumError = false;
        normal.close(); expectError(() -> transport.readBlocks(1, data, 512), "detach");
        expectError(() -> new BotTransport(new Device(123, 200), 0).initialize(), "invalid sector size");
        expectError(() -> new BotTransport(new Device(65536, Long.MAX_VALUE-1), 0).initialize(), "capacity overflow");
        System.out.println("USB BOT/SCSI: " + checks + " checks passed");
    }
}
