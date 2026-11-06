import dev.usbreader.core.*;
import java.nio.file.*;
import java.util.*;

/** Reference images from EfiFs; expected EFI bytes independently extracted with isoinfo. */
public final class ExtendedFilesystemTest {
    static Entry find(Entry[] rows, String name) {
        return Arrays.stream(rows).filter(e -> e.name.equalsIgnoreCase(name)).findFirst()
            .orElseThrow(() -> new AssertionError("Missing " + name + " in " + Arrays.toString(Arrays.stream(rows).map(e -> e.name).toArray())));
    }
    public static void main(String[] args) throws Exception {
        Path dir=Paths.get("tests/fixtures/extended");
        byte[] expected=Files.readAllBytes(dir.resolve("bootx64.efi"));
        String[] formats=args.length>0?args:new String[]{"xfs","btrfs","f2fs","zfs","jfs","reiserfs","erofs","squash4","udf","nilfs2","minix","minix2","minix3","romfs","hfs","hfsplus","ufs1","ufs2"};
        int failed=0;
        for(String format:formats) {
            try(NativeFs fs=new NativeFs(new NativeIntegrationTest.FileSource(dir.resolve(format+(format.equals("udf")?".iso":".img")),512))) {
                Volume[] all=fs.volumes();
                Volume v=Arrays.stream(all).filter(Volume::readable).findFirst().orElseThrow(()->new AssertionError("No readable volume: "+all[0].error));
                long parent=v.rootInode;
                for(String path:(format.equals("zfs")?new String[]{"EFI","@","Boot"}:new String[]{"EFI","Boot"})) parent=find(fs.list(v.index,parent),path).inode;
                Entry file=find(fs.list(v.index,parent),"bootx64.efi");
                byte[] actual=NativeIntegrationTest.read(fs,v,file);
                NativeIntegrationTest.check(Arrays.equals(expected,actual),format+" exported bytes differ: "+actual.length+" vs "+expected.length);
                // Seek backwards and read a non-sector-aligned range as preview/export does.
                byte[] tail=new byte[139];
                NativeIntegrationTest.check(fs.read(v.index,file.inode,701,tail,tail.length)==tail.length,"random read length");
                NativeIntegrationTest.check(Arrays.equals(tail,Arrays.copyOfRange(expected,701,840)),"random read bytes");
                System.out.println("PASS "+format+" ("+v.type+"): directory traversal + "+actual.length+" verified bytes");
            } catch(Throwable e) {failed++;System.err.println("FAIL "+format+": "+e);}
        }
        if(failed>0)throw new AssertionError(failed+" formats failed");
        System.out.println("Extended filesystems: "+NativeIntegrationTest.checks+" checks passed");
    }
}
