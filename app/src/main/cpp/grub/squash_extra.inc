/* Additional SquashFS v4 codecs (on-disk IDs 5=LZ4, 6=Zstd). */
#include <lz4.h>
#include <zstd.h>
static grub_ssize_t extra_decompress(char *in, grub_size_t insize,
                                     grub_off_t off, char *out, grub_size_t len,
                                     struct grub_squash_data *data, int codec) {
  grub_size_t capacity = data->blksz < 8192 ? 8192 : data->blksz;
  if (capacity > 1048576 || off > capacity || len > capacity - off ||
      insize > 1048576)
    return grub_error(GRUB_ERR_BAD_FS, "Invalid SquashFS block size"), -1;
  char *buffer = grub_malloc(capacity);
  if (!buffer)
    return -1;
  grub_size_t decoded;
  if (codec == 5) {
    int n = LZ4_decompress_safe(in, buffer, insize, capacity);
    if (n < 0) {
      grub_free(buffer);
      return grub_error(GRUB_ERR_BAD_COMPRESSED_DATA, "Invalid LZ4 block"), -1;
    }
    decoded = n;
  } else {
    decoded = ZSTD_decompress(buffer, capacity, in, insize);
    if (ZSTD_isError(decoded)) {
      grub_free(buffer);
      return grub_error(GRUB_ERR_BAD_COMPRESSED_DATA, "Invalid Zstd block"), -1;
    }
  }
  grub_size_t avail = off < decoded ? decoded - off : 0;
  if (avail > len)
    avail = len;
  if (avail)
    grub_memcpy(out, buffer + off, avail);
  if (avail < len)
    grub_memset(out + avail, 0, len - avail);
  grub_free(buffer);
  return len;
}
static grub_ssize_t lz4_decompress(char *i, grub_size_t n, grub_off_t o,
                                   char *b, grub_size_t l,
                                   struct grub_squash_data *d) {
  return extra_decompress(i, n, o, b, l, d, 5);
}
static grub_ssize_t zstd_decompress(char *i, grub_size_t n, grub_off_t o,
                                    char *b, grub_size_t l,
                                    struct grub_squash_data *d) {
  return extra_decompress(i, n, o, b, l, d, 6);
}
