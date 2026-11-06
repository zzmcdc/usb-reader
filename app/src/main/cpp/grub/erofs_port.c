/* liberofs userspace reader exposed through GRUB's directory/file interface. */
#define HAVE_LINUX_TYPES_H 1
#define _FILE_OFFSET_BITS 64
#include <erofs/config.h>
#include <erofs/dir.h>
#include <erofs/io.h>
#include <grub/file.h>
#include <grub/fs.h>
#include <grub/misc.h>
#include <grub/mm.h>
struct erofs_mount {
  struct erofs_sb_info sb;
  struct erofs_inode inode;
  struct erofs_vfile file;
};
static ssize_t disk_pread(struct erofs_vfile *vf, void *buf, size_t len,
                          u64 offset) {
  grub_disk_t disk;
  memcpy(&disk, vf->payload, sizeof(disk));
  return grub_disk_read(disk, offset / 512, offset % 512, len, buf)
             ? -EIO
             : (ssize_t)len;
}
static ssize_t deny_write(struct erofs_vfile *vf, const void *buf, u64 off,
                          size_t len) {
  (void)vf;
  (void)buf;
  (void)off;
  (void)len;
  return -EROFS;
}
static struct erofs_vfops disk_ops = {.pread = disk_pread,
                                      .pwrite = deny_write};
static void unmount_erofs(struct erofs_mount *m) {
  if (m) {
    erofs_put_super(&m->sb);
    grub_free(m);
  }
}
static struct erofs_mount *mount_erofs(grub_device_t dev, const char *path) {
  struct erofs_mount *m = grub_zalloc(sizeof(*m));
  if (!m)
    return NULL;
  m->sb.bdev.ops = &disk_ops;
  memcpy(m->sb.bdev.payload, &dev->disk, sizeof(dev->disk));
  m->sb.devsz = grub_disk_native_sectors(dev->disk) * 512;
  int rc = erofs_read_superblock(&m->sb);
  if (!rc) {
    m->inode.sbi = &m->sb;
    rc = erofs_ilookup(path, &m->inode);
  }
  if (rc) {
    unmount_erofs(m);
    grub_error(GRUB_ERR_BAD_FS, "EROFS: %s", strerror(-rc));
    return NULL;
  }
  return m;
}
struct directory_ctx {
  struct erofs_dir_context dir;
  grub_fs_dir_hook_t hook;
  void *data;
};
static int directory_entry(struct erofs_dir_context *ctx) {
  struct directory_ctx *d = (struct directory_ctx *)ctx;
  if (ctx->dot_dotdot)
    return 0;
  char name[256];
  memcpy(name, ctx->dname, ctx->de_namelen);
  name[ctx->de_namelen] = 0;
  struct erofs_inode inode = {.sbi = ctx->dir->sbi, .nid = ctx->de_nid};
  int rc = erofs_read_inode_from_disk(&inode);
  if (rc)
    return rc;
  struct grub_dirhook_info info = {0};
  info.dir = S_ISDIR(inode.i_mode);
  info.mtime = inode.i_mtime;
  info.mtimeset = 1;
  info.inodeset = 1;
  info.inode = inode.nid;
  return d->hook(name, &info, d->data) ? 1 : 0;
}
static grub_err_t directory(grub_device_t dev, const char *path,
                            grub_fs_dir_hook_t hook, void *data) {
  struct erofs_mount *m = mount_erofs(dev, path);
  if (!m)
    return grub_errno;
  struct directory_ctx ctx = {.dir = {.dir = &m->inode, .cb = directory_entry},
                              .hook = hook,
                              .data = data};
  int rc =
      S_ISDIR(m->inode.i_mode) ? erofs_iterate_dir(&ctx.dir, false) : -ENOTDIR;
  unmount_erofs(m);
  if (rc < 0)
    return grub_error(GRUB_ERR_BAD_FS, "EROFS directory: %s", strerror(-rc));
  return GRUB_ERR_NONE;
}
static grub_err_t open_file(grub_file_t file, const char *path) {
  struct erofs_mount *m = mount_erofs(file->device, path);
  if (!m)
    return grub_errno;
  int rc =
      S_ISREG(m->inode.i_mode) ? erofs_iopen(&m->file, &m->inode) : -EINVAL;
  if (rc) {
    unmount_erofs(m);
    return grub_error(GRUB_ERR_BAD_FILE_TYPE,
                      "EROFS: not a readable regular file");
  }
  file->size = m->inode.i_size;
  file->data = m;
  return GRUB_ERR_NONE;
}
static grub_ssize_t read_file(grub_file_t file, char *buf, grub_size_t n) {
  struct erofs_mount *m = file->data;
  int rc = erofs_pread(&m->file, buf, n, file->offset);
  if (rc) {
    grub_error(GRUB_ERR_READ_ERROR, "EROFS read: %s", strerror(-rc));
    return -1;
  }
  return n;
}
static grub_err_t close_file(grub_file_t file) {
  unmount_erofs(file->data);
  file->data = NULL;
  return GRUB_ERR_NONE;
}
static struct grub_fs erofs_driver = {.name = "erofs",
                                      .fs_dir = directory,
                                      .fs_open = open_file,
                                      .fs_read = read_file,
                                      .fs_close = close_file};
void grub_erofs_init(void) {
  erofs_init_configure();
  cfg.c_dbg_lvl = -1;
  grub_fs_register(&erofs_driver);
}
