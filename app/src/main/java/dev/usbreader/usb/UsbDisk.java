package dev.usbreader.usb;

import android.hardware.usb.*;
import dev.usbreader.core.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class UsbDisk implements BlockSource {
    public static final class Target {
        public final UsbDevice device;
        public final UsbInterface intf;
        Target(UsbDevice device, UsbInterface intf) { this.device = device; this.intf = intf; }
        public String name() {
            String product = device.getProductName();
            return (product == null ? "USB 存储设备" : product) + " · 接口 " + intf.getId();
        }
    }
    public static List<Target> targets(UsbManager manager) {
        List<Target> result = new ArrayList<>();
        for (UsbDevice device : manager.getDeviceList().values()) {
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface intf = device.getInterface(i);
                if (intf.getInterfaceClass() == 8 && intf.getInterfaceSubclass() == 6 && intf.getInterfaceProtocol() == 0x50)
                    result.add(new Target(device, intf));
            }
        }
        return result;
    }
    private final Pipe pipe;
    private final BotTransport transport;
    private final byte[] scratch;
    private volatile boolean closed;
    public final int maxLun;

    public UsbDisk(UsbManager manager, Target target, int lun) throws IOException {
        pipe = new Pipe(manager, target);
        try {
            byte[] value = new byte[1];
            int n = pipe.connection.controlTransfer(0xa1, 0xfe, 0, target.intf.getId(), value, 1, 2000);
            maxLun = n == 1 ? Math.min(15, value[0] & 255) : 0;
            if (lun > maxLun) throw new IOException("此设备的逻辑单元范围为 0–" + maxLun);
            transport = new BotTransport(pipe, lun); transport.initialize();
            scratch = new byte[Math.max(128 * 1024, transport.sectorSize())];
        } catch (Exception e) { pipe.close(); throw e; }
    }
    @Override public long size() { return transport.size(); }
    @Override public int sectorSize() { return transport.sectorSize(); }
    @Override public synchronized void read(long offset, byte[] target, int length) throws IOException {
        if (closed) throw new IOException("U盘已断开");
        if (offset < 0 || length < 0 || length > target.length || offset > size() - length)
            throw new IOException("读取范围超出磁盘边界");
        int done = 0, sector = sectorSize();
        while (done < length) {
            if (closed) throw new IOException("U盘已断开");
            long position = offset + done;
            int skip = (int) (position % sector);
            int copy = Math.min(length - done, scratch.length - skip);
            int readLength = ((skip + copy + sector - 1) / sector) * sector;
            transport.readBlocks(position / sector, scratch, readLength);
            System.arraycopy(scratch, skip, target, done, copy); done += copy;
        }
    }
    // May be called from the UI to interrupt an in-flight bulk transfer after detach/cancel.
    @Override public void close() { closed = true; pipe.close(); }

    private static final class Pipe implements BulkPipe {
        final UsbDeviceConnection connection;
        final UsbInterface intf;
        final UsbEndpoint in, out;
        volatile boolean closed;
        Pipe(UsbManager manager, Target target) throws IOException {
            intf = target.intf;
            UsbEndpoint input = null, output = null;
            for (int i = 0; i < intf.getEndpointCount(); i++) {
                UsbEndpoint ep = intf.getEndpoint(i);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) input = ep; else output = ep;
                }
            }
            if (input == null || output == null) throw new IOException("未找到 USB 批量传输端点");
            in = input; out = output;
            connection = manager.openDevice(target.device);
            if (connection == null) throw new IOException("无法打开设备，请允许 USB 访问");
            if (!connection.claimInterface(intf, true)) {
                connection.close(); throw new IOException("无法独占 USB 接口，请先从系统存储设置弹出 U盘后重试");
            }
            if (intf.getAlternateSetting() != 0 && !connection.setInterface(intf)) {
                close(); throw new IOException("无法选择 USB 接口");
            }
        }
        private void check() throws IOException { if (closed) throw new IOException("USB 连接已关闭"); }
        @Override public int transferIn(byte[] b, int offset, int length) throws IOException {
            check(); return connection.bulkTransfer(in, b, offset, length, 4000);
        }
        @Override public int transferOut(byte[] b, int offset, int length) throws IOException {
            check(); return connection.bulkTransfer(out, b, offset, length, 4000);
        }
        private void clear(UsbEndpoint ep) throws IOException {
            check();
            if (connection.controlTransfer(2, 1, 0, ep.getAddress(), null, 0, 2000) < 0)
                throw new IOException("无法恢复 USB 端点");
        }
        @Override public void clearInHalt() throws IOException { clear(in); }
        @Override public void reset() throws IOException {
            check();
            if (connection.controlTransfer(0x21, 0xff, 0, intf.getId(), null, 0, 2000) < 0)
                throw new IOException("USB 传输复位失败");
            clear(in); clear(out);
        }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true; connection.releaseInterface(intf); connection.close();
        }
    }
}
