#include "jni_strings.h"
#include <algorithm>
#include <array>
#include <chrono>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <zlib.h>
extern "C" {
#include "fsapi.h"
#include <grub/disk.h>
#include <grub/file.h>
#include <grub/misc.h>
void usbreader_grub_init(void);
}
namespace {
constexpr size_t PAGE = 64 * 1024;
std::mutex
    engineMutex; // GRUB has global module/error state, across Java sessions.
std::once_flag initFlag;
struct Image {
  JavaVM *vm = nullptr;
  jobject source = nullptr;
  jbyteArray buffer = nullptr;
  jmethodID read = nullptr;
  jthrowable error = nullptr;
  uint64_t size = 0;
  unsigned sector = 512;
  uint64_t ticks = 0, operations = 0;
  std::chrono::steady_clock::time_point deadline;
  struct Page {
    uint64_t address = UINT64_MAX, age = 0;
    std::array<char, PAGE> data{};
  };
  std::vector<Page> cache{32};
  JNIEnv *env() {
    JNIEnv *e = nullptr;
    vm->GetEnv(reinterpret_cast<void **>(&e), JNI_VERSION_1_6);
    return e;
  }
  ~Image() {
    if (auto *e = env()) {
      e->DeleteGlobalRef(source);
      e->DeleteGlobalRef(buffer);
      if (error)
        e->DeleteGlobalRef(error);
    }
  }
  void begin() {
    operations = 0;
    deadline = std::chrono::steady_clock::now() + std::chrono::seconds(45);
  }
  bool get(uint64_t offset, size_t length, void *output) {
    if (error || offset > size || length > size - offset)
      return false;
    if (++operations > 2000000 || std::chrono::steady_clock::now() > deadline)
      return false;
    auto *out = static_cast<char *>(output);
    while (length) {
      uint64_t address = offset & ~(uint64_t(PAGE) - 1);
      Page *page = nullptr;
      for (auto &p : cache)
        if (p.address == address) {
          page = &p;
          break;
        }
      if (!page) {
        page =
            &*std::min_element(cache.begin(), cache.end(),
                               [](auto &a, auto &b) { return a.age < b.age; });
        page->address = UINT64_MAX;
        auto n = static_cast<jsize>(std::min<uint64_t>(PAGE, size - address));
        auto *e = env();
        e->CallVoidMethod(source, read, static_cast<jlong>(address), buffer, n);
        if (!e->ExceptionCheck())
          e->GetByteArrayRegion(buffer, 0, n,
                                reinterpret_cast<jbyte *>(page->data.data()));
        if (e->ExceptionCheck()) {
          auto cause = e->ExceptionOccurred();
          e->ExceptionClear();
          error = static_cast<jthrowable>(e->NewGlobalRef(cause));
          e->DeleteLocalRef(cause);
          return false;
        }
        page->address = address;
      }
      page->age = ++ticks;
      size_t inside = offset - address, n = std::min(length, PAGE - inside);
      std::memcpy(out, page->data.data() + inside, n);
      out += n;
      offset += n;
      length -= n;
    }
    return true;
  }
};
struct Node {
  std::string path;
  bool directory;
  uint64_t size;
  bool regular = false;
};
struct Volume {
  Image *image;
  uint64_t offset, length;
  std::string description, type = "Unknown", error;
  grub_disk disk{};
  grub_device device{};
  grub_fs_t fs = nullptr;
  fsapi_volume *refs = nullptr;
  fsapi_node *refsRoot = nullptr;
  std::vector<Node> nodes{{"", false, 0}, {"/", true, 0}};
  std::unordered_map<std::string, uint64_t> ids{{"/", 1}};
  Volume(Image *i, uint64_t o, uint64_t n, std::string d)
      : image(i), offset(o), length(n), description(std::move(d)) {
    disk.data = this;
    disk.name = "usb";
    disk.total_sectors = n / 512;
    disk.log_sector_size = 9;
    device.disk = &disk;
  }
  ~Volume() {
    if (refs)
      fsapi_volume_unmount(&refs);
  }
  bool get(uint64_t off, size_t n, void *out) {
    return off <= length && n <= length - off &&
           image->get(offset + off, n, out);
  }
  uint64_t remember(std::string path, bool dir, uint64_t size) {
    auto old = ids.find(path);
    if (old != ids.end())
      return old->second;
    if (nodes.size() >= 1000000)
      throw std::runtime_error("Session exceeds 1000000 entries");
    uint64_t id = nodes.size();
    ids.emplace(path, id);
    nodes.push_back({std::move(path), dir, size});
    return id;
  }
  Node node(uint64_t id) {
    if (!id || id >= nodes.size())
      throw std::runtime_error("Unknown file handle");
    return nodes[id];
  }
};
struct Session {
  std::unique_ptr<Image> image;
  std::vector<std::unique_ptr<Volume>> volumes;
  bool scanned = false;
  grub_file file{};
  bool fileOpen = false;
  int fileVolume = -1;
  uint64_t fileId = 0;
  ~Session() { closeFile(); }
  void closeFile() {
    if (fileOpen)
      file.fs->fs_close(&file);
    file = {};
    fileOpen = false;
  }
};
std::string lastError() {
  return grub_errmsg[0] ? grub_errmsg : "Cannot read filesystem";
}
void resetError() {
  grub_errno = GRUB_ERR_NONE;
  grub_errmsg[0] = 0;
}
Session *session(jlong h) {
  if (!h)
    throw std::runtime_error("Device is closed");
  auto *s = reinterpret_cast<Session *>(h);
  s->image->begin();
  return s;
}
bool checkIo(JNIEnv *env, Session *s) {
  if (s->image->error) {
    env->Throw(s->image->error);
    return false;
  }
  return !env->ExceptionCheck();
}
uint32_t le32(const unsigned char *b) {
  return uint32_t(b[0]) | (uint32_t(b[1]) << 8) | (uint32_t(b[2]) << 16) |
         (uint32_t(b[3]) << 24);
}
uint64_t le64(const unsigned char *b) {
  return le32(b) | (uint64_t(le32(b + 4)) << 32);
}
// Some GRUB drivers return the iterator stop status as an error; probe by full
// bounded listing.
int probeHook(const char *, const grub_dirhook_info *, void *count) {
  return ++*static_cast<unsigned *>(count) > 100000;
}
void probe(Volume &v) {
  unsigned char magic[512]{};
  if (!v.get(0, sizeof(magic), magic)) {
    v.error = "Cannot read volume header";
    return;
  }
  if (!std::memcmp(magic + 3, "ReFS", 4)) {
    int err = fsapi_volume_mount(&v, SYS_TRUE, nullptr, &v.refs, &v.refsRoot,
                                 nullptr);
    v.type = "ReFS";
    if (err)
      v.error = "ReFS: " + std::string(std::strerror(err)) +
                " (unsupported version/features or damaged volume)";
    return;
  }
  for (grub_fs_t fs = grub_fs_list; fs; fs = fs->next) {
    resetError();
    unsigned entries = 0;
    if (fs->fs_dir && !fs->fs_dir(&v.device, "/", probeHook, &entries) &&
        entries <= 100000) {
      v.error.clear();
      v.fs = fs;
      v.type = fs->name;
      if (v.type == "ext2") {
        unsigned char sb[104]{};
        if (v.get(1024, sizeof(sb), sb))
          v.type = (le32(sb + 96) & 0x40)
                       ? "ext4"
                       : ((le32(sb + 92) & 4) ? "ext3" : "ext2");
      }
      if (v.type == "fat") {
        unsigned bps = magic[11] | (magic[12] << 8), spc = magic[13],
                 reserved = magic[14] | (magic[15] << 8), fats = magic[16],
                 root = magic[17] | (magic[18] << 8);
        uint64_t total = magic[19] | (magic[20] << 8);
        if (!total)
          total = le32(magic + 32);
        uint64_t fat = magic[22] | (magic[23] << 8);
        if (!fat)
          fat = le32(magic + 36);
        uint64_t overhead = reserved + fats * fat +
                            (root * 32 + bps - 1) / (bps ? bps : 1),
                 clusters =
                     spc && total >= overhead ? (total - overhead) / spc : 0;
        v.type =
            clusters < 4085 ? "fat12" : (clusters < 65525 ? "fat16" : "fat32");
      }
      return;
    }
    if (std::strcmp(fs->name, "btrfs") == 0) {
      std::string why = lastError();
      char signature[8]{};
      if (v.length > 65536 + 72 && v.get(65536 + 64, 8, signature) &&
          !std::memcmp(signature, "_BHRfS_M", 8))
        v.error = "Btrfs: " + why;
    }
    if (v.image->error)
      break;
  }
  if (v.error.empty())
    v.error =
        "Unsupported filesystem/feature, encrypted volume, or damaged metadata";
}
void addVolume(Session *s, uint64_t off, uint64_t length,
               const std::string &description) {
  if (!length || off > s->image->size || length > s->image->size - off)
    throw std::runtime_error("Partition outside device boundaries");
  if (s->volumes.size() >= 256)
    throw std::runtime_error("Too many partitions (maximum 256)");
  auto v = std::make_unique<Volume>(s->image.get(), off, length, description);
  probe(*v);
  s->volumes.push_back(std::move(v));
}
#include "partitions.inc"
Volume &volume(Session *s, jint index) {
  if (index < 0 || static_cast<size_t>(index) >= s->volumes.size() ||
      !s->volumes[index]->error.empty())
    throw std::runtime_error("This partition cannot be read");
  return *s->volumes[index];
}
struct Row {
  std::string name;
  uint64_t id, size;
  int64_t time;
  bool directory, regular;
};
struct Listing {
  Volume *v;
  std::string parent;
  std::vector<Row> rows;
  std::string error;
};
int collect(const char *name, const grub_dirhook_info *info, void *ptr) {
  auto &list = *static_cast<Listing *>(ptr);
  try {
    if (!name || !std::strcmp(name, ".") || !std::strcmp(name, ".."))
      return 0;
    if (std::strchr(name, '/') || std::strlen(name) > 4096)
      throw std::runtime_error("Invalid directory entry name");
    if (list.rows.size() >= 100000)
      throw std::runtime_error("Directory exceeds 100000 entries");
    uint64_t id = list.v->remember(
        list.parent + (list.parent.back() == '/' ? "" : "/") + name, info->dir,
        0);
    list.rows.push_back(
        {name, id, 0, info->mtimeset ? info->mtime : 0, info->dir != 0, false});
    return 0;
  } catch (const std::exception &e) {
    list.error = e.what();
    return 1;
  }
}
int collectRefs(void *ptr, const char *name, size_t length,
                fsapi_node_attributes *attrs) {
  std::string n(name, length);
  grub_dirhook_info info{};
  info.dir = attrs->is_directory;
  info.mtimeset = 1;
  info.mtime = attrs->last_data_change_time.tv_sec;
  auto &list = *static_cast<Listing *>(ptr);
  size_t before = list.rows.size();
  if (collect(n.c_str(), &info, ptr))
    return EIO;
  if (list.rows.size() > before) {
    auto &r = list.rows.back();
    r.size = attrs->size;
    r.regular =
        !r.directory && (!(attrs->valid & FSAPI_NODE_ATTRIBUTE_TYPE_MODE) ||
                         S_ISREG(attrs->mode));
    list.v->nodes[r.id].size = r.size;
    list.v->nodes[r.id].regular = r.regular;
  }
  return 0;
}
fsapi_node *refsLookup(Volume &v, const std::string &path) {
  fsapi_node *node = nullptr;
  int err = fsapi_node_lookup(v.refs, nullptr, path.c_str(), path.size(), &node,
                              nullptr);
  if (err)
    throw std::runtime_error("ReFS lookup: " + std::string(std::strerror(err)));
  return node;
}
std::vector<Row> listFiles(Volume &v, uint64_t id) {
  Node node = v.node(id);
  if (!node.directory)
    throw std::runtime_error("Not a directory");
  Listing list{&v, node.path, {}, {}};
  resetError();
  if (v.refs) {
    fsapi_node *dir = refsLookup(v, node.path);
    fsapi_node_attributes attrs{};
    attrs.requested = static_cast<fsapi_node_attribute_types>(
        FSAPI_NODE_ATTRIBUTE_TYPE_SIZE |
        FSAPI_NODE_ATTRIBUTE_TYPE_LAST_DATA_CHANGE_TIME |
        FSAPI_NODE_ATTRIBUTE_TYPE_MODE);
    int err = fsapi_node_list(v.refs, dir, &attrs, &list, collectRefs);
    fsapi_node_release(v.refs, &dir, 1);
    if (err)
      throw std::runtime_error("ReFS directory: " +
                               std::string(std::strerror(err)));
  } else {
    if (v.fs->fs_dir(&v.device, node.path.c_str(), collect, &list))
      throw std::runtime_error(lastError());
    // Open only after enumeration, avoiding recursive entry into a driver
    // iterator.
    for (auto &row : list.rows)
      if (!row.directory) {
        grub_file f{};
        f.device = &v.device;
        f.fs = v.fs;
        f.type = GRUB_FILE_TYPE_GET_SIZE;
        resetError();
        if (!v.fs->fs_open(&f, v.nodes[row.id].path.c_str())) {
          row.size = f.size;
          row.regular = true;
          v.nodes[row.id].size = f.size;
          v.nodes[row.id].regular = true;
          v.fs->fs_close(&f);
        }
        if (v.image->error)
          break;
      }
  }
  if (!list.error.empty())
    throw std::runtime_error(list.error);
  return list.rows;
}
} // namespace
extern "C" grub_err_t grub_disk_read(grub_disk_t disk, grub_disk_addr_t sector,
                                     grub_off_t offset, grub_size_t size,
                                     void *buf) {
  auto *v = static_cast<Volume *>(disk->data);
  if (sector > UINT64_MAX / 512 || offset > UINT64_MAX - sector * 512 ||
      sector * 512 + offset > v->length ||
      size > v->length - (sector * 512 + offset))
    return grub_error(GRUB_ERR_OUT_OF_RANGE, "Block range outside volume");
  if (!v->get(sector * 512 + offset, size, buf))
    return grub_error(GRUB_ERR_READ_ERROR,
                      "Device read error or operation limit");
  return GRUB_ERR_NONE;
}
extern "C" int usbreader_refs_read(void *d, u64 off, size_t n, void *out) {
  return static_cast<Volume *>(d)->get(off, n, out) ? 0 : EIO;
}
extern "C" int usbreader_refs_size(void *d, u64 *n) {
  *n = static_cast<Volume *>(d)->length;
  return 0;
}
extern "C" int usbreader_refs_sector(void *d, u32 *s) {
  *s = static_cast<Volume *>(d)->image->sector;
  return 0;
}

// The block adapter already accepts arbitrary byte ranges. Avoid the upstream
// buffer helper's extra 4 KiB alignment (which double-applies unaligned offsets).
static int refsReadIo(void *opaque, sys_device *dev, u64 offset, size_t size) {
  auto *ctx = static_cast<fsapi_iohandler_buffer_context *>(opaque);
  if (size > ctx->remaining_size ||
      usbreader_refs_read(dev, offset, size, ctx->buf.rw))
    return EIO;
  ctx->buf.rw += size;
  ctx->remaining_size -= size;
  return 0;
}
static int refsCopyIo(void *opaque, const void *data, size_t size) {
  auto *ctx = static_cast<fsapi_iohandler_buffer_context *>(opaque);
  if (size > ctx->remaining_size)
    return EIO;
  std::memcpy(ctx->buf.rw, data, size);
  ctx->buf.rw += size;
  ctx->remaining_size -= size;
  return 0;
}
static int refsHoleIo(void *opaque, size_t size) {
  auto *ctx = static_cast<fsapi_iohandler_buffer_context *>(opaque);
  if (size > ctx->remaining_size)
    return EIO;
  std::memset(ctx->buf.rw, 0, size);
  ctx->buf.rw += size;
  ctx->remaining_size -= size;
  return 0;
}

extern "C" JNIEXPORT jlong JNICALL Java_dev_usbreader_core_NativeFs_open(
    JNIEnv *env, jclass, jobject source, jlong size, jint sector) {
  std::lock_guard<std::mutex> lock(engineMutex);
  try {
    std::call_once(initFlag, usbreader_grub_init);
    if (size <= 0 || sector < 512 || sector > 65536 ||
        (sector & (sector - 1)) || size % sector)
      throw std::runtime_error("Invalid device geometry");
    auto s = std::make_unique<Session>();
    s->image = std::make_unique<Image>();
    auto &image = *s->image;
    env->GetJavaVM(&image.vm);
    image.size = size;
    image.sector = sector;
    image.source = env->NewGlobalRef(source);
    jclass cls = env->GetObjectClass(source);
    image.read = env->GetMethodID(cls, "read", "(J[BI)V");
    env->DeleteLocalRef(cls);
    auto buffer = env->NewByteArray(PAGE);
    image.buffer = static_cast<jbyteArray>(env->NewGlobalRef(buffer));
    env->DeleteLocalRef(buffer);
    if (env->ExceptionCheck())
      return 0;
    return reinterpret_cast<jlong>(s.release());
  } catch (const std::exception &e) {
    fail(env, e.what());
    return 0;
  }
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_usbreader_core_NativeFs_volumes(JNIEnv *env, jclass, jlong handle) {
  std::lock_guard<std::mutex> lock(engineMutex);
  Session *s = nullptr;
  try {
    s = session(handle);
    if (!checkIo(env, s))
      return nullptr;
    scan(s);
    if (!checkIo(env, s))
      return nullptr;
    jclass cls = env->FindClass("dev/usbreader/core/Volume");
    auto ctor = env->GetMethodID(
        cls, "<init>",
        "(IJJJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    auto result = env->NewObjectArray(s->volumes.size(), cls, nullptr);
    if (!result || !ctor)
      return nullptr;
    for (size_t i = 0; i < s->volumes.size(); i++) {
      auto &v = *s->volumes[i];
      auto type = utf8(env, v.type), desc = utf8(env, v.description),
           error = utf8(env, v.error);
      auto item = env->NewObject(
          cls, ctor, static_cast<jint>(i), static_cast<jlong>(v.offset),
          static_cast<jlong>(v.length), jlong(1), type, desc, error);
      env->SetObjectArrayElement(result, i, item);
      env->DeleteLocalRef(item);
      env->DeleteLocalRef(type);
      env->DeleteLocalRef(desc);
      env->DeleteLocalRef(error);
      if (env->ExceptionCheck())
        return nullptr;
    }
    env->DeleteLocalRef(cls);
    return result;
  } catch (const std::exception &e) {
    if (!s || checkIo(env, s))
      fail(env, e.what());
    return nullptr;
  }
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_dev_usbreader_core_NativeFs_list(
    JNIEnv *env, jclass, jlong handle, jint index, jlong id) {
  std::lock_guard<std::mutex> lock(engineMutex);
  Session *s = nullptr;
  try {
    s = session(handle);
    if (!checkIo(env, s))
      return nullptr;
    auto rows = listFiles(volume(s, index), id);
    if (!checkIo(env, s))
      return nullptr;
    jclass cls = env->FindClass("dev/usbreader/core/Entry");
    auto ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;JJJZZ)V");
    auto result = env->NewObjectArray(rows.size(), cls, nullptr);
    if (!result || !ctor)
      return nullptr;
    for (size_t i = 0; i < rows.size(); i++) {
      auto &r = rows[i];
      auto name = utf8(env, r.name);
      auto item = env->NewObject(
          cls, ctor, name, static_cast<jlong>(r.id), static_cast<jlong>(r.size),
          static_cast<jlong>(r.time), static_cast<jboolean>(r.directory),
          static_cast<jboolean>(r.regular));
      env->SetObjectArrayElement(result, i, item);
      env->DeleteLocalRef(item);
      env->DeleteLocalRef(name);
      if (env->ExceptionCheck())
        return nullptr;
    }
    env->DeleteLocalRef(cls);
    return result;
  } catch (const std::exception &e) {
    if (!s || checkIo(env, s))
      fail(env, e.what());
    return nullptr;
  }
}
extern "C" JNIEXPORT jint JNICALL Java_dev_usbreader_core_NativeFs_read(
    JNIEnv *env, jclass, jlong handle, jint index, jlong id, jlong offset,
    jbyteArray output, jint length) {
  std::lock_guard<std::mutex> lock(engineMutex);
  Session *s = nullptr;
  try {
    s = session(handle);
    if (!checkIo(env, s))
      return -1;
    auto &v = volume(s, index);
    Node node = v.node(id);
    if (!node.regular || offset < 0 || length < 0 || length > 1024 * 1024 ||
        length > env->GetArrayLength(output))
      throw std::runtime_error("Unreadable file or invalid read range");
    if (static_cast<uint64_t>(offset) >= node.size)
      return 0;
    length = static_cast<jint>(std::min<uint64_t>(length, node.size - offset));
    std::vector<char> buffer(length);
    ssize_t n = 0;
    resetError();
    if (v.refs) {
      fsapi_node *file = refsLookup(v, node.path);
      fsapi_iohandler_buffer_context ctx{};
      ctx.buf.rw = buffer.data();
      ctx.remaining_size = length;
      ctx.is_read = SYS_TRUE;
      fsapi_iohandler io{};
      io.context = &ctx;
      io.handle_io = refsReadIo;
      io.copy_data = refsCopyIo;
      io.handle_hole = refsHoleIo;
      int err = fsapi_node_read(v.refs, file, offset, length, &io);
      fsapi_node_release(v.refs, &file, 1);
      if (err)
        throw std::runtime_error("ReFS read: " +
                                 std::string(std::strerror(err)));
      n = length - ctx.remaining_size;
    } else {
      if (!s->fileOpen || s->fileVolume != index ||
          s->fileId != static_cast<uint64_t>(id)) {
        s->closeFile();
        s->file.device = &v.device;
        s->file.fs = v.fs;
        s->file.type = GRUB_FILE_TYPE_CAT;
        if (v.fs->fs_open(&s->file, node.path.c_str()))
          throw std::runtime_error(lastError());
        s->fileOpen = true;
        s->fileVolume = index;
        s->fileId = id;
      }
      s->file.offset = offset;
      n = v.fs->fs_read(&s->file, buffer.data(), length);
    }
    if (!checkIo(env, s))
      return -1;
    if (n < 0)
      throw std::runtime_error(lastError());
    if (n != length)
      throw std::runtime_error("Unexpected short file read");
    env->SetByteArrayRegion(output, 0, n,
                            reinterpret_cast<const jbyte *>(buffer.data()));
    return n;
  } catch (const std::exception &e) {
    if (!s || checkIo(env, s))
      fail(env, e.what());
    return -1;
  }
}
extern "C" JNIEXPORT void JNICALL
Java_dev_usbreader_core_NativeFs_close(JNIEnv *, jclass, jlong handle) {
  std::lock_guard<std::mutex> lock(engineMutex);
  delete reinterpret_cast<Session *>(handle);
}
