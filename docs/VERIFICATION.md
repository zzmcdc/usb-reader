# 2.0 验证记录

日期：2026-11-06。全部使用普通磁盘镜像或协议模拟输入，没有格式化真实 U盘或操作用户文件。

| 检查 | 结果 |
|---|---|
| Android 构建 | assembleDebug 成功，arm64-v8a / armeabi-v7a / x86_64 |
| Android Lint | No issues found |
| APK 交付校验 | apksigner 验证 v2 签名成功；zipalign 16 KiB 检查通过；arm64 原生库 LOAD 段按 16 KiB 对齐 |
| USB BOT/SCSI | Linux 和 Android 各 241 项通过 |
| 基础 JNI 文件系统 | Linux 和 Android 各 488 项通过 |
| 新格式和压缩变体 | Linux 和 Android 各 288 项通过 |
| 18 种参考文件系统 | Linux 和 Android 各 144 项通过 |
| 真实 ReFS 3.14 | Linux 和 Android 各 36 项通过 |
| 总计 | 每个平台 1,197 项检查通过 |
| 内存检查 | 上述全部文件系统测试使用 Linux AddressSanitizer 通过；JVM 下关闭 leak detection，未将其当作内存泄漏测试 |
| Android JNI 检查 | 所有文件系统测试启用 -Xcheck:jni，使用 APK 的真实 x86_64 原生库 |
| 物理 USB | 尚未进行真实 U盘、OTG、多手机或不同供电条件的兼容验证 |

## 格式与测试数据

基础格式：FAT12/16/32、exFAT、ext2/3/4、NTFS、ISO9660。验证文件完整内容、中文/emoji、子目录、空文件、
ext4 的 >4 GiB 文件尺寸和稀疏读取、MBR/GPT512/GPT4096、未知/截断镜像、JNI 异常传播和关闭行为。

新生成镜像：Btrfs、F2FS、XFS；SquashFS 4 的 gzip/LZO/XZ/LZ4/Zstd；EROFS LZ4。
使用真实 mkfs / sload / mksquashfs 工具写入测试内容，读取后与源文件逐字节比较。

EfiFs 参考镜像：XFS、Btrfs、F2FS、ZFS、JFS、ReiserFS、EROFS、SquashFS、UDF、NILFS2、
Minix 1/2/3、ROMFS、HFS、HFS+、UFS1/2。每个镜像都进入子目录并读取 57,923 字节的 bootx64.efi，
另检查非对齐随机读取。预期内容通过独立 isoinfo 从参考 ISO 提取，SHA-256：

```text
a6d0a727e1b6084935b2d05e378728da0d7a4af70de2c774ff26ab779efe5fd2
```

## ReFS 真实样本

来源：[forefst reference disks](https://github.com/xbqt/forefst/tree/main/analysis/samples/disks/wininsiderrefs8gtest2)，
固定提交 d4d5fd28942eb14ebe11d90f4eb9342f5e3d7b92。Windows 创建的 8 GiB 磁盘，GPT、ReFS 3.14、
64 KiB 簇、SHA-256 元数据校验类型。Git LFS 压缩文件 SHA-256：

```text
274d0387656a1f6e36e1128e60d9baa0f71bc37d511914cc68f238323ea61d64
```

预期文件由独立的 forefst 解析器提取，清单见 [refs-expected.tsv](../tests/refs-expected.tsv)。
验证 12、182、578777、560984 字节的四个文件，包含驻留和非驻留数据、深层目录及 65537 字节非对齐分块。
Android 端使用同一完整镜像的无损稀疏区段表示，保留所有已分配字节与零区段；不合成目录或文件内容。

真实样本暴露并回归验证的修复：
1. 元数据合并读取原本会把四个 64 KiB 簇读入一个 64 KiB 缓冲区；现逐片读取且限制节点边界。
2. 合并读后的索引只前进一个簇；移除这一合并路径，由底层块缓存完成 USB 读取合并。
3. 上游缓冲助手对非对齐偏移重复移位；使用接受任意字节偏移的有界回调。
4. 区段尾部取整会跨越到不相邻物理区段；按真实剩余字节读取。

ReFS 仍标为实验性：一个 3.14 样本不能替代各版本、损坏状态、快照、压缩/去重及存储池的完整兼容验证。

## 界面与交付

Android 11 / API 30 模拟器检查新版启动、系统镜像选择、XFS 分区与目录浏览、通过系统文件选择器导出。
[新版首页](screenshots/home-v2.png) · [XFS 文件浏览](screenshots/files-v2.png) · [导出成功](screenshots/export-v2.png)

从 XFS 导出的 xfs-v2-payload.bin 长度为 350003 字节，拉回宿主后 SHA-256 与原始 payload.bin 完全一致：

```text
6ad0e755208b46acefa7d3870e46f3f049a67d0c2f1bf081554fbf1315cd27ce
```

APK 与完整源码包校验值以 [v2.0.0 的 SHA256SUMS](https://github.com/zzmcdc/usb-reader/releases/download/v2.0.0/SHA256SUMS) 为准，本地副本在 dist/SHA256SUMS。
模拟器测试不能替代真实 USB 设备的插拔、供电和手机厂商兼容验证。

## 重跑

```bash
./scripts/test.sh
./gradlew :app:assembleDebug :app:lintDebug
./scripts/test-android.sh emulator-5554
python3 scripts/fetch-refs-fixture.py
./scripts/test-refs.sh emulator-5554
```

复现 ASan：用 C/C++ 的 -fsanitize=address -fno-omit-frame-pointer 和共享库链接 -fsanitize=address
构建到 build/asan；启动 Java 前 LD_PRELOAD 同时加载 libasan.so 和 libstdc++.so，
ASAN_OPTIONS=detect_leaks=0:abort_on_error=1，依次执行 NativeIntegrationTest、
新增格式参数、ExtendedFilesystemTest 和 RefsIntegrationTest。只预加载 libasan 会使 JVM 动态加载
C++ 库时的异常拦截器缺少 __cxa_throw；这是测试运行环境问题，应同时预加载 C++ 运行时。
