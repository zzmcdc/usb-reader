package dev.usbreader.core;

public final class Volume {
    public final int index;
    public final long offset, length, rootInode;
    public final String type, description, error;
    public Volume(int index, long offset, long length, long rootInode,
                  String type, String description, String error) {
        this.index = index; this.offset = offset; this.length = length;
        this.rootInode = rootInode; this.type = type;
        this.description = description; this.error = error;
    }
    public boolean readable() { return error.isEmpty(); }
}
