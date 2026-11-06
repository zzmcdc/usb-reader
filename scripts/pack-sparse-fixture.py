#!/usr/bin/env python3
"""Pack all allocated disk-image extents; holes retain their exact zero semantics."""
import errno
import os
import pathlib
import struct
import sys

source=pathlib.Path(sys.argv[1])
target=pathlib.Path(sys.argv[2])
with source.open("rb") as src, target.open("wb") as out:
    size=os.fstat(src.fileno()).st_size
    out.write(struct.pack(">QQ",0x5553425350415253,size))
    offset=0
    while offset<size:
        try:
            offset=os.lseek(src.fileno(),offset,os.SEEK_DATA)
        except OSError as e:
            if e.errno==errno.ENXIO:break
            raise
        end=os.lseek(src.fileno(),offset,os.SEEK_HOLE)
        while offset<end:
            length=min(end-offset,1024*1024)
            out.write(struct.pack(">QI",offset,length))
            data=os.pread(src.fileno(),length,offset)
            if len(data)!=length:raise IOError("Short fixture read")
            out.write(data)
            offset+=length
print(f"Packed {size} bytes into {target.stat().st_size} bytes")
