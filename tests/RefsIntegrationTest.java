import dev.usbreader.core.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Optional real Windows-created ReFS corpus. The manifest is produced by independent forefst. */
public final class RefsIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path image=Paths.get("tests/fixtures/extended/refs314.img");
        BlockSource source=Files.exists(image)?new NativeIntegrationTest.FileSource(image,512):new SparseImageSource(Paths.get("tests/fixtures/extended/refs314.sparse"));
        try(NativeFs fs=new NativeFs(source)) {
            Volume[] all=fs.volumes();
            for(Volume v:all)System.out.println(v.type+" "+v.offset+" "+v.length+" "+v.error);
            Volume v=Arrays.stream(all).filter(Volume::readable).findFirst().orElseThrow();
            Entry[] root=fs.list(v.index,v.rootInode);
            for(Entry e:root)System.out.println("ROOT "+e.name+" "+e.size+" dir="+e.directory);
            Path manifest=Paths.get("tests/fixtures/extended/refs-expected.tsv");
            if(!Files.exists(manifest))throw new AssertionError("Independent expected-content manifest is missing");
            for(String line:Files.readAllLines(manifest)) {
                String[] parts=line.split("\t");String path=parts[0];long parent=v.rootInode;Entry e=null;
                for(String name:path.split("/")) {e=NativeIntegrationTest.find(fs.list(v.index,parent),name);parent=e.inode;}
                NativeIntegrationTest.check(e!=null&&e.regular,"ReFS regular file: "+path);
                NativeIntegrationTest.check(e.size==Long.parseLong(parts[1]),"ReFS size: "+path);
                MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65537];long off=0;
                while(off<e.size) {int n=fs.read(v.index,e.inode,off,buffer,(int)Math.min(buffer.length,e.size-off));NativeIntegrationTest.check(n>0,"ReFS short read");digest.update(buffer,0,n);off+=n;}
                StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
                NativeIntegrationTest.check(hash.toString().equals(parts[2]),"ReFS content mismatch: "+path);
                NativeIntegrationTest.check(fs.read(v.index,e.inode,e.size,buffer,1)==0,"ReFS EOF");
                System.out.println("PASS ReFS "+path+" / "+e.size+" bytes / "+hash);
            }
        }
        System.out.println("ReFS: "+NativeIntegrationTest.checks+" checks passed");
    }
}
