package dev.usbreader.core;

public final class Entry {
    public final String name;
    public final long inode, size, modified;
    public final boolean directory, regular;
    public Entry(String name, long inode, long size, long modified, boolean directory, boolean regular) {
        this.name = name; this.inode = inode; this.size = size; this.modified = modified;
        this.directory = directory; this.regular = regular;
    }
}
