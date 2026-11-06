set(EROFS "${PROJECT_ROOT}/third_party/erofs-utils")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/config.h" "/* Platform features are provided by CMake. */\n")
set(EROFS_SOURCES "")
foreach(NAME config io super namei data zmap decompress dir metabox xattr cache compress compressor
    hashmap sha256 blobchunk fragments dedupe dedupe_ext xxhash bitops diskbuf inode
    compressor_deflate kite_deflate uuid uuid_unparse block_list)
    list(APPEND EROFS_SOURCES "${EROFS}/lib/${NAME}.c")
endforeach()
add_library(erofsfs STATIC ${EROFS_SOURCES})
# The reader needs decompression, never LZ4/Zstd filesystem creation backends.
set_property(SOURCE "${EROFS}/lib/compressor.c" APPEND PROPERTY COMPILE_OPTIONS -ULZ4_ENABLED -UHAVE_LIBZSTD)
target_include_directories(erofsfs PUBLIC "${EROFS}/include" PRIVATE "${CMAKE_CURRENT_BINARY_DIR}" "${PROJECT_ROOT}/third_party/lz4/lib" "${PROJECT_ROOT}/third_party/grub/grub-core/lib/zstd")
target_compile_definitions(erofsfs PRIVATE _GNU_SOURCE=1 _FILE_OFFSET_BITS=64
    HAVE_LINUX_TYPES_H=1 HAVE_SYS_SYSMACROS_H=1 HAVE_UNISTD_H=1 HAVE_ENDIAN_H=1
    HAVE_PREAD64=1 HAVE_PWRITE64=1 HAVE_STRUCT_STAT_ST_ATIM=1
    HAVE_MEMRCHR=1 HAVE_STRCHRNUL=1 HAVE_ZLIB=1 HAVE_LIBZSTD=1 LZ4_ENABLED=1 PACKAGE_VERSION="pinned")
target_compile_options(erofsfs PRIVATE -ffunction-sections -fdata-sections -Wno-unused-result)
target_link_libraries(erofsfs PUBLIC z)
