# U盘通读 · Android 2.0

无需 root 的 Android USB 文件读取应用。通过 Android USB Host / SCSI Bulk-Only 直接读盘，
在应用内解析文件系统，浏览分区和目录、预览文本/十六进制、导出文件到手机。
U盘只读访问，无网络权限、无全盘存储权限。

[下载 APK](https://github.com/zzmcdc/usb-reader/releases/download/v2.0.0/usb-reader-debug.apk) · [完整源码](https://github.com/zzmcdc/usb-reader/releases/download/v2.0.0/usb-reader-2.0-source.zip) · [验证记录](docs/VERIFICATION.md) · [第三方许可](THIRD_PARTY_NOTICES.md)

## 常见 Windows / Linux 文件系统

这里的“格式”指磁盘文件系统，不是 PDF、图片等文件类型。文件内容可以导出，
预览目前仅提供文本与十六进制，不负责解码所有文档/媒体类型。

| 系统 / 用途 | 已接入的文件系统 | 实际验证 |
|---|---|---|
| Windows / 通用 U盘 | FAT12、FAT16、FAT32、exFAT、NTFS | 目录、中文/emoji、文件内容逐字节比对 |
| Windows ReFS | ReFS（实验性） | Windows 创建的 ReFS 3.14、64 KiB 簇、SHA-256 元数据变体；驻留和非驻留文件与独立工具 SHA-256 一致 |
| Linux 主流 | ext2、ext3、ext4、XFS、Btrfs、F2FS | 真实 mkfs 镜像、中文/emoji、子目录、文件内容校验；ext4 的 >4 GiB 稀疏文件 |
| Linux 存储池 / 较旧格式 | ZFS、JFS、ReiserFS、NILFS2、Minix 1/2/3 | 上游参考镜像目录、完整文件内容和随机读取；ZFS 为单设备参考池 |
| 光盘 / 系统镜像 | ISO9660、UDF、SquashFS 4、EROFS、ROMFS | 参考镜像内容校验；SquashFS 的 gzip/LZO/XZ/LZ4/Zstd，以及 EROFS LZ4 压缩文件另有测试 |
| 其他系统 | HFS、HFS+、UFS1、UFS2 | 参考镜像目录、完整文件内容和随机读取 |

**以上格式均有真实读取路径和通过的镜像测试。不能承诺任意版本、任意特性、任意设备均可读取。**
尤其 ReFS 上游本身仍在逆向开发，保留实验性标识；验证一个 3.14 样本不能推断所有 ReFS 版本均已验证。

具体边界：
- 常规单设备、未加密卷。支持 MBR 主分区/EBR 逻辑分区、GPT 及 512/4096 字节扇区；GPT 校验头和分区表 CRC，可尝试备用头。
- Btrfs / ZFS 不组装多个物理设备。ZFS 限于上游 GRUB 支持的池特性；不承诺新特性、加密或缺少成员的池。
- EROFS 已启用 LZ4、Deflate、Zstd 解码，实际压缩样本验证为 LZ4；未启用 LZMA。SquashFS 限 v4，上述五种压缩均已验证。
- ReFS 普通主数据流读取；不提供 ADS、ACL、历史快照、去重/压缩容器、修复或完整元数据导出。无法读取的文件应保留错误状态，不能据此认定完整卷均兼容。
- NTFS 不解密 EFS / BitLocker，也不承诺 WOF / CompactOS 等扩展。ext 不重放日志，异常断电卷可能呈现旧数据或报错。
- 不支持 LUKS、BitLocker 等加密层，LVM、Windows 动态卷、跨设备 RAID / Storage Spaces；这些不是单独的文件系统格式。
- 不支持 UAS-only USB 接口。USB 设备必须提供 SCSI transparent / Bulk-Only 接口。
- APFS、Reiser4、JFFS2/UBIFS/YAFFS 等非本表格式不在本版范围；swap、procfs、sysfs、tmpfs 也不是普通 U盘目录文件系统。
- 不提供系统级挂载；其他应用通过导出的文件访问内容。镜像入口仅接受本地、可随机读取的原始 .img/.raw/.dd/.iso，不解包 qcow2、E01、VHD/VHDX 或压缩容器。

## 构建与安装

从 GitHub 获取项目与固定版本的原生依赖（无需递归获取依赖自身的子模块）：

```bash
git clone https://github.com/zzmcdc/usb-reader.git
cd usb-reader
git submodule update --init --depth 1
```

也可下载上方完整源码包，已包含编译所需依赖，无需初始化子模块。

本机 SDK：`/home/work/android-sdk`。Gradle：`/home/work/android-tools/gradle-8.14/bin/gradle`。

JDK 17/21；SDK / target 36；Build Tools 35.0.0；NDK 28.2.13676358；CMake 3.22.1；
Gradle 8.14、AGP 8.9.1。最低 Android 8.0（API 26），含 arm64-v8a、armeabi-v7a、x86_64。

```bash
./scripts/bootstrap.sh
./gradlew :app:assembleDebug :app:lintDebug
# 本机也可使用：
/home/work/android-tools/gradle-8.14/bin/gradle :app:assembleDebug :app:lintDebug --offline
# 由你选择目标设备安装刚构建的 APK：
/home/work/android-sdk/platform-tools/adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

产物为 `app/build/outputs/apk/debug/app-debug.apk`，交付副本为 `dist/usb-reader-debug.apk`。
这是可安装的调试签名版本；正式分发应使用自己的 release 签名。
原生库使用 NDK r28 的 16 KiB 页面对齐。固定源码版本见 `third_party/dependencies.json`。
源码包包含实际编译依赖，缺失时 `scripts/fetch-deps.py` 可重新获取固定提交，不重置已有检出。
Git 仓库不保存构建产物；发布页提供 APK、包含依赖的完整源码包及 SHA256SUMS。
使用 `python3 scripts/package-source.py` 可在本地 `dist/` 重新生成源码包和交付校验值。

如果 sdkmanager 无法解析 `socks5h` 代理，在可直连 Google 的网络中清除 HTTP_PROXY / HTTPS_PROXY /
ALL_PROXY（含小写变量），或使用 sdkmanager 自身的 socks 代理参数。

## 使用

1. 用 OTG 连接 U盘，点击“连接并读取”，允许 Android USB 授权；普通 U盘选择 LUN 0。
2. 选择分区，进入目录，点击文件预览或导出。导出目的地由 Android 系统文件选择器选择。
3. 导出时显示进度，可取消并尝试删除未完成文件；提供方拒绝删除时会提示手动处理。
4. 点击“断开”释放接口。导出期间保持应用在前台，进程被系统终止时不能保证清理部分文件。

若系统占用 U盘接口，先在系统存储设置中弹出再连接应用。是否能独占 USB 接口还取决于手机厂商实现。
参考截图见验证记录。物理 OTG、读卡器和不同手机组合尚需实机验收。

## 测试

```bash
# Ubuntu 工具：e2fsprogs dosfstools mtools exfatprogs ntfs-3g genisoimage
# btrfs-progs f2fs-tools xfsprogs squashfs-tools erofs-utils cmake ninja-build JDK
./scripts/test.sh
./scripts/test-android.sh emulator-5554

# 可选：下载约 396 MB 的真实 ReFS 测试压缩镜像（展开为稀疏 8 GiB）
python3 scripts/fetch-refs-fixture.py
./scripts/test-refs.sh
```

测试只操作普通镜像文件，不格式化真实块设备。脚本兼容 `build/test-tools/root` 下局部解包的测试工具。

- USB BOT/SCSI：241 项协议、错误恢复、64 位寻址、边界及断连检查。
- 基础 JNI：488 项 FAT/ext/exFAT/NTFS/ISO、MBR/GPT、Unicode、稀疏文件、异常和生命周期检查。
- 新格式 mkfs：288 项 Btrfs/F2FS/XFS、五种 SquashFS 压缩和 EROFS LZ4 检查。
- EfiFs 参考镜像：144 项，18 种格式；预期文件由独立的 isoinfo 从参考 ISO 提取并固定 SHA-256。
- ReFS：36 项；预期数据由独立 forefst 工具提取并固定 SHA-256，涵盖 65,537 字节分块跨区段读取。
- Linux AddressSanitizer 检查用于原生内存错误定位；Android 使用真实 APK 原生库及 `-Xcheck:jni`。

## 实现与维护

`core/BotTransport.java` 实现只读 USB BOT/SCSI 指令白名单；`usb/UsbDisk.java` 处理端点和扇区对齐；
`ReaderModel.java` 管理串行 I/O、导出、取消和配置变化；`MainActivity.java` 提供中文界面与系统文件选择器。

`native_multi.cpp` 统一 JNI、分区/文件句柄、64 位字节读取和 2 MiB 缓存。
`partitions.inc` 处理 MBR/EBR/GPT。引擎全局互斥，避免 GRUB 全局错误状态污染其他会话。
`grub/`、`refs/`、`erofs/` 含宿主适配及可审查的构建补丁，上游检出不被直接改写。

本次为 ReFS 修复了元数据读合并越界、碎片节点步进、文件区段尾部错误取整；
用原始字节回调避免上游缓冲助手对非对齐偏移重复移位。
这些修复均由真实镜像测试发现，详见验证记录。

本版已替换原来的 Sleuth Kit 引擎。应用整体按 GPL-3.0-or-later 分发，原始 glue/UI 另保留 MIT 许可；
发布 APK 时必须同时遵守对应源码分发要求。完整许可见 LICENSE、LICENSE-MIT 和 THIRD_PARTY_NOTICES.md。

## 格式范围依据

- [Microsoft 文件系统功能对照](https://learn.microsoft.com/en-us/windows/win32/fileio/filesystem-functionality-comparison)
- [Microsoft ReFS 概述](https://learn.microsoft.com/en-us/windows-server/storage/refs/refs-overview)
- [Linux 文件系统文档](https://docs.kernel.org/filesystems/index.html)
- [GNU GRUB 源码](https://gitlab.freedesktop.org/gnu-grub/grub)
- [refsprogs](https://github.com/unsound/refsprogs)
- [ReFS 参考镜像与独立解析器](https://github.com/xbqt/forefst/tree/main/analysis/samples)
