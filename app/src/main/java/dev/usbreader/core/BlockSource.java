package dev.usbreader.core;

import java.io.Closeable;
import java.io.IOException;

/** Read-only byte-addressable device. A read returns exactly length bytes or throws. */
public interface BlockSource extends Closeable {
    long size();
    int sectorSize();
    void read(long offset, byte[] target, int length) throws IOException;
}
