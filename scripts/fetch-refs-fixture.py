#!/usr/bin/env python3
"""Optional public ReFS 3.14 fixture; pinned Git LFS digest, resumable range download."""
import concurrent.futures
import hashlib
import pathlib
import shutil
import subprocess

ROOT=pathlib.Path(__file__).resolve().parents[1]
REV="d4d5fd28942eb14ebe11d90f4eb9342f5e3d7b92"
URL=f"https://media.githubusercontent.com/media/xbqt/forefst/{REV}/analysis/samples/disks/wininsiderrefs8gtest2/wininsiderrefs8gtest2.raw.zst"
SIZE=395591911
SHA="274d0387656a1f6e36e1128e60d9baa0f71bc37d511914cc68f238323ea61d64"
OUT=ROOT/"tests/fixtures/extended"
OUT.mkdir(parents=True,exist_ok=True)
archive=OUT/"refs314.complete.zst"
def valid(path):
    if not path.exists() or path.stat().st_size!=SIZE:return False
    with path.open("rb") as f:return hashlib.file_digest(f,"sha256").hexdigest()==SHA
if not valid(archive):
    chunks=ROOT/"build/refs-download"
    chunks.mkdir(parents=True,exist_ok=True)
    step=4*1024*1024
    def fetch(i):
        a=i*step;b=min(SIZE,a+step)-1;dest=chunks/str(i)
        if dest.exists() and dest.stat().st_size==b-a+1:return dest
        subprocess.run(["curl","-L","--fail","--retry","3","--max-time","120","-sS",
                        "-r",f"{a}-{b}",URL+f"?piece={i}","-o",str(dest)],check=True)
        if dest.stat().st_size!=b-a+1:raise IOError(f"Invalid range response: {i}")
        print(f"Downloaded part {i+1}",flush=True)
        return dest
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        parts=list(pool.map(fetch,range((SIZE+step-1)//step)))
    temp=archive.with_suffix(".partial")
    with temp.open("wb") as out:
        for part in parts:
            with part.open("rb") as src:shutil.copyfileobj(src,out)
    if not valid(temp):raise IOError("ReFS fixture SHA-256 mismatch")
    temp.replace(archive)
image=OUT/"refs314.img"
# Always derive the raw bytes from the verified archive, using sparse zero ranges.
subprocess.run(["zstd","-d","--sparse","-f",str(archive),"-o",str(image)],check=True)
shutil.copyfile(ROOT/"tests/refs-expected.tsv",OUT/"refs-expected.tsv")
print("ReFS fixture verified and ready; source: https://github.com/xbqt/forefst")
