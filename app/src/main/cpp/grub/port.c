/* POSIX hosting layer for the read-only GRUB filesystem modules. */
#include <grub/command.h>
#include <grub/crypto.h>
#include <grub/disk.h>
#include <grub/diskfilter.h>
#include <grub/env.h>
#include <grub/extcmd.h>
#include <grub/file.h>
#include <grub/misc.h>
#include <grub/mm.h>
#include <grub/partition.h>
#include <grub/term.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <zlib.h>
grub_raid6_recover_func_t grub_raid6_recover_func;
grub_err_t grub_diskfilter_read_node(const struct grub_diskfilter_node *node,
                                     grub_disk_addr_t sector, grub_size_t size,
                                     char *buf) {
  (void)node;
  (void)sector;
  (void)size;
  (void)buf;
  return grub_error(GRUB_ERR_UNKNOWN_DEVICE, "RAID member unavailable");
}

grub_fs_t grub_fs_list;
grub_file_filter_t grub_file_filters[GRUB_FILE_FILTER_MAX];
grub_disk_read_hook_t grub_file_progress_hook;
struct grub_term_input *grub_term_inputs;
static void quiet_puts(const char *s) { (void)s; }
void (*grub_xputs)(const char *) = quiet_puts;
void grub_refresh(void) {}
int grub_getkey(void) { return 0; }
void grub_exit(void) { abort(); }
const char *grub_env_get(const char *name) {
  (void)name;
  return NULL;
}
int grub_is_lockdown(void) { return 0; }
void *grub_malloc(grub_size_t n) {
  if (n > 256u * 1024u * 1024u) {
    grub_error(GRUB_ERR_OUT_OF_MEMORY, "Allocation exceeds 256 MiB");
    return NULL;
  }
  void *p = malloc(n ? n : 1);
  if (!p)
    grub_error(GRUB_ERR_OUT_OF_MEMORY, "Out of memory");
  return p;
}
void *grub_zalloc(grub_size_t n) {
  void *p = grub_malloc(n);
  if (p)
    memset(p, 0, n);
  return p;
}
void *grub_calloc(grub_size_t n, grub_size_t s) {
  if (s && n > (grub_size_t)-1 / s) {
    grub_error(GRUB_ERR_OUT_OF_MEMORY, "Allocation overflow");
    return NULL;
  }
  return grub_zalloc(n * s);
}
void grub_free(void *p) { free(p); }
void *grub_realloc(void *p, grub_size_t n) {
  if (n > 256u * 1024u * 1024u) {
    grub_error(GRUB_ERR_OUT_OF_MEMORY, "Allocation exceeds 256 MiB");
    return NULL;
  }
  void *q = realloc(p, n ? n : 1);
  if (!q)
    grub_error(GRUB_ERR_OUT_OF_MEMORY, "Out of memory");
  return q;
}
grub_uint64_t grub_dl_ref(grub_dl_t m) {
  (void)m;
  return 1;
}
grub_uint64_t grub_dl_unref(grub_dl_t m) {
  (void)m;
  return 1;
}
grub_uint64_t grub_disk_native_sectors(grub_disk_t d) {
  return d->total_sectors << (d->log_sector_size - 9);
}
/* A session exposes one physical volume. Never substitute another disk for a
 * missing RAID member. */
grub_device_t grub_device_open(const char *name) {
  (void)name;
  grub_error(GRUB_ERR_UNKNOWN_DEVICE, "Additional volume required");
  return NULL;
}
grub_err_t grub_device_close(grub_device_t d) {
  (void)d;
  return GRUB_ERR_NONE;
}
int grub_device_iterate(grub_device_iterate_hook_t hook, void *data) {
  (void)hook;
  (void)data;
  return 0;
}
char *grub_file_get_device_name(const char *name) {
  (void)name;
  return NULL;
}
/* Bootloader commands are deliberately not exposed in this Android reader. */
grub_err_t grub_env_set(const char *k, const char *v) {
  (void)k;
  (void)v;
  return GRUB_ERR_NONE;
}
grub_command_t grub_register_command_prio(const char *n, grub_command_func_t f,
                                          const char *s, const char *d, int p) {
  (void)n;
  (void)f;
  (void)s;
  (void)d;
  (void)p;
  return NULL;
}
void grub_unregister_command(grub_command_t c) { (void)c; }
grub_extcmd_t grub_register_extcmd(const char *n, grub_extcmd_func_t f,
                                   grub_command_flags_t fl, const char *s,
                                   const char *d,
                                   const struct grub_arg_option *p) {
  (void)n;
  (void)f;
  (void)fl;
  (void)s;
  (void)d;
  (void)p;
  return NULL;
}
void grub_unregister_extcmd(grub_extcmd_t c) { (void)c; }

typedef struct {
  uint64_t value;
  unsigned char digest[8];
} CrcContext;
static void crc_init(void *p, unsigned int flags) {
  (void)flags;
  ((CrcContext *)p)->value = UINT64_MAX;
}
static void crc32_write(void *p, const void *in, grub_size_t n) {
  CrcContext *c = p;
  const unsigned char *b = in;
  while (n--) {
    c->value ^= *b++;
    for (int i = 0; i < 8; i++)
      c->value =
          (uint32_t)c->value >> 1 ^ (0xedb88320u & (0u - (c->value & 1)));
  }
}
static void crc64_write(void *p, const void *in, grub_size_t n) {
  CrcContext *c = p;
  const unsigned char *b = in;
  while (n--) {
    c->value ^= *b++;
    for (int i = 0; i < 8; i++)
      c->value = c->value >> 1 ^
                 (UINT64_C(0xc96c5795d7870f42) & (0u - (c->value & 1)));
  }
}
static void crc32_final(void *p) {
  CrcContext *c = p;
  uint32_t v = ~(uint32_t)c->value;
  for (int i = 0; i < 4; i++)
    c->digest[i] = (unsigned char)(v >> (24 - i * 8));
}
static void crc64_final(void *p) {
  CrcContext *c = p;
  uint64_t v = ~c->value;
  for (int i = 0; i < 8; i++)
    c->digest[i] = (unsigned char)(v >> (56 - i * 8));
}
static unsigned char *crc_read(void *p) { return ((CrcContext *)p)->digest; }
static const gcry_md_spec_t crc32_spec = {.name = "CRC32",
                                          .mdlen = 4,
                                          .init = crc_init,
                                          .write = crc32_write,
                                          .final = crc32_final,
                                          .read = crc_read,
                                          .contextsize = sizeof(CrcContext)};
static const gcry_md_spec_t crc64_spec = {.name = "CRC64",
                                          .mdlen = 8,
                                          .init = crc_init,
                                          .write = crc64_write,
                                          .final = crc64_final,
                                          .read = crc_read,
                                          .contextsize = sizeof(CrcContext)};
const gcry_md_spec_t *grub_crypto_lookup_md_by_name(const char *n) {
  if (!strcmp(n, "CRC32"))
    return &crc32_spec;
  if (!strcmp(n, "CRC64"))
    return &crc64_spec;
  return NULL;
}
void grub_crypto_hash(const gcry_md_spec_t *h, void *out, const void *in,
                      grub_size_t n) {
  CrcContext c;
  h->init(&c, 0);
  h->write(&c, in, n);
  h->final(&c);
  memcpy(out, h->read(&c), h->mdlen);
}

static grub_ssize_t inflate_slice(char *in, grub_size_t insize, grub_off_t off,
                                  char *out, grub_size_t size, int window) {
  if (insize > UINT_MAX || off > 128u * 1024u * 1024u ||
      size > 128u * 1024u * 1024u - off)
    return grub_error(GRUB_ERR_BAD_COMPRESSED_DATA,
                      "Oversized compressed block"),
           -1;
  z_stream z = {0};
  if (inflateInit2(&z, window) != Z_OK)
    return -1;
  z.next_in = (Bytef *)in;
  z.avail_in = (uInt)insize;
  unsigned char scratch[32768];
  grub_size_t copied = 0;
  while (copied < size) {
    grub_off_t before = z.total_out;
    z.next_out = scratch;
    z.avail_out = sizeof(scratch);
    int rc = inflate(&z, Z_NO_FLUSH);
    grub_size_t produced = sizeof(scratch) - z.avail_out;
    if (before + produced > off) {
      grub_size_t skip = before < off ? (grub_size_t)(off - before) : 0;
      grub_size_t take = produced - skip;
      if (take > size - copied)
        take = size - copied;
      memcpy(out + copied, scratch + skip, take);
      copied += take;
    }
    if (rc == Z_STREAM_END)
      break;
    if (rc != Z_OK || !produced) {
      inflateEnd(&z);
      grub_error(GRUB_ERR_BAD_COMPRESSED_DATA, "Invalid deflate stream");
      return -1;
    }
  }
  inflateEnd(&z);
  return (grub_ssize_t)copied;
}
grub_ssize_t grub_zlib_decompress(char *in, grub_size_t n, grub_off_t off,
                                  char *out, grub_size_t size) {
  return inflate_slice(in, n, off, out, size, 15);
}
grub_ssize_t grub_deflate_decompress(char *in, grub_size_t n, grub_off_t off,
                                     char *out, grub_size_t size) {
  return inflate_slice(in, n, off, out, size, -15);
}

void usbreader_grub_init(void) {
#define INIT(name)                                                             \
  extern void grub_##name##_init(void);                                        \
  grub_##name##_init();
  INIT(fat)
  INIT(exfat) INIT(ext2) INIT(ntfs) INIT(ntfscomp) INIT(xfs) INIT(btrfs)
      INIT(f2fs) INIT(jfs) INIT(reiserfs) INIT(iso9660) INIT(udf) INIT(squash4)
          INIT(erofs) INIT(hfs) INIT(hfsplus) INIT(hfspluscomp) INIT(ufs1)
              INIT(ufs2) INIT(nilfs2) INIT(minix) INIT(minix2) INIT(minix3)
                  INIT(romfs) INIT(zfs)
#undef INIT
}
