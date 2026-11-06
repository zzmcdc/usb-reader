package dev.usbreader.core;

import java.io.Closeable;
import java.io.IOException;

/** USB-specific operations kept outside the testable BOT/SCSI protocol. */
public interface BulkPipe extends Closeable {
    int transferIn(byte[] data, int offset, int length) throws IOException;
    int transferOut(byte[] data, int offset, int length) throws IOException;
    void clearInHalt() throws IOException;
    void reset() throws IOException;
}
