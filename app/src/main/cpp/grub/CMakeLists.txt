set(GRUB "${PROJECT_ROOT}/third_party/grub")
file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/include/grub")
file(READ "${GRUB}/include/grub/disk.h" DISK_HEADER)
string(REPLACE "for (pull = 0; pull < GRUB_DISK_PULL_MAX; pull++)"
    "for (pull = (grub_disk_pull_t)0; pull < GRUB_DISK_PULL_MAX; pull = (grub_disk_pull_t)(pull + 1))" DISK_HEADER "${DISK_HEADER}")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/include/grub/disk.h" "${DISK_HEADER}")
file(READ "${GRUB}/grub-core/fs/squash4.c" SQUASH_SOURCE)
string(REPLACE "static grub_ssize_t\nzlib_decompress" "#include \"squash_extra.inc\"\nstatic grub_ssize_t\nzlib_decompress" SQUASH_SOURCE "${SQUASH_SOURCE}")
string(REPLACE "switch (sb.compression)\n    {" "switch (sb.compression)\n    {\n    case grub_cpu_to_le16_compile_time(5): data->decompress=lz4_decompress; break;\n    case grub_cpu_to_le16_compile_time(6): data->decompress=zstd_decompress; break;" SQUASH_SOURCE "${SQUASH_SOURCE}")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/squash4.c" "${SQUASH_SOURCE}")
set(GRUB_MODULES fat exfat ext2 ntfs ntfscomp xfs btrfs f2fs jfs reiserfs
    iso9660 udf hfs hfsplus hfspluscomp ufs ufs2 nilfs2 minix minix2 minix3 romfs)
set(GRUB_SOURCES port.c erofs_port.c "${CMAKE_CURRENT_BINARY_DIR}/squash4.c" "${PROJECT_ROOT}/third_party/lz4/lib/lz4.c")
foreach(MODULE IN LISTS GRUB_MODULES)
    list(APPEND GRUB_SOURCES "${GRUB}/grub-core/fs/${MODULE}.c")
endforeach()
list(APPEND GRUB_SOURCES
    "${GRUB}/grub-core/fs/fshelp.c"
    "${GRUB}/grub-core/kern/list.c"
    "${GRUB}/grub-core/kern/err.c"
    "${GRUB}/grub-core/kern/misc.c"
    "${GRUB}/grub-core/lib/crc.c"
    "${GRUB}/grub-core/lib/datetime.c"
    "${GRUB}/grub-core/lib/minilzo/minilzo.c"
    "${GRUB}/grub-core/lib/xzembed/xz_dec_lzma2.c"
    "${GRUB}/grub-core/lib/xzembed/xz_dec_bcj.c"
    "${GRUB}/grub-core/lib/xzembed/xz_dec_stream.c"
    "${GRUB}/grub-core/fs/zfs/zfs.c"
    "${GRUB}/grub-core/fs/zfs/zfs_fletcher.c"
    "${GRUB}/grub-core/fs/zfs/zfs_lz4.c"
    "${GRUB}/grub-core/fs/zfs/zfs_lzjb.c"
    "${GRUB}/grub-core/fs/zfs/zfs_sha256.c"
    "${GRUB}/grub-core/disk/raid6_recover.c")
file(GLOB ZSTD_SOURCES "${GRUB}/grub-core/lib/zstd/common/*.c" "${GRUB}/grub-core/lib/zstd/decompress/*.c")
list(APPEND GRUB_SOURCES ${ZSTD_SOURCES})
add_library(grubfs STATIC ${GRUB_SOURCES})
target_include_directories(grubfs PUBLIC "${CMAKE_CURRENT_BINARY_DIR}/include" "${CMAKE_CURRENT_SOURCE_DIR}" "${GRUB}/include"
    PRIVATE "${GRUB}/grub-core/lib/minilzo" "${GRUB}/grub-core/lib/zstd" "${GRUB}/grub-core/lib/xzembed" "${PROJECT_ROOT}/third_party/lz4/lib")
target_compile_definitions(grubfs PUBLIC GRUB_KERNEL=1 PRIVATE GRUB=1 LZO_CFG_FREESTANDING=1 ZSTD_DISABLE_ASM=1)
target_compile_options(grubfs PRIVATE -fno-strict-aliasing -Wno-address-of-packed-member -ffunction-sections -fdata-sections)
target_link_libraries(grubfs PUBLIC erofsfs z)
