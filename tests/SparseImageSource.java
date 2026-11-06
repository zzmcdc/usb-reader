import dev.usbreader.core.BlockSource;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Lossless allocated-range fixture; absent ranges are known zero-filled disk space. */
public final class SparseImageSource implements BlockSource {
    private final RandomAccessFile input;
    private final long size;
    private final TreeMap<Long, long[]> ranges = new TreeMap<>();
    public SparseImageSource(Path path) throws IOException {
        input = new RandomAccessFile(path.toFile(), "r");
        if(input.readLong()!=0x5553425350415253L)throw new IOException("Bad sparse fixture");
        size=input.readLong();
        while(input.getFilePointer()<input.length()) {
            long start=input.readLong(),length=input.readInt()&0xffffffffL,position=input.getFilePointer();
            if(start<0||length==0||start>size-length||position>input.length()-length)throw new IOException("Bad sparse extent");
            ranges.put(start,new long[]{start+length,position});input.seek(position+length);
        }
    }
    public long size(){return size;}
    public int sectorSize(){return 512;}
    public void close() throws IOException{input.close();}
    public void read(long offset,byte[] target,int length)throws IOException {
        if(offset<0||length<0||length>target.length||offset>size-length)throw new IOException("Out of range");
        Arrays.fill(target,0,length,(byte)0);
        Map.Entry<Long,long[]> entry=ranges.floorEntry(offset);
        if(entry==null||entry.getValue()[0]<=offset)entry=ranges.ceilingEntry(offset);
        long end=offset+length;
        while(entry!=null&&entry.getKey()<end) {
            long start=Math.max(offset,entry.getKey()),stop=Math.min(end,entry.getValue()[0]);
            if(stop>start){input.seek(entry.getValue()[1]+start-entry.getKey());input.readFully(target,(int)(start-offset),(int)(stop-start));}
            entry=ranges.higherEntry(entry.getKey());
        }
    }
}
