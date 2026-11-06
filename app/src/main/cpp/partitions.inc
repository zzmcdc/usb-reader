// Bounds-checked MBR/EBR and CRC-checked GPT. Offsets are always bytes in the
// JNI API.
bool gpt(Session *s, unsigned sector, uint64_t headerLba) {
  Image &img = *s->image;
  std::vector<unsigned char> head(sector);
  if (headerLba >= img.size / sector ||
      !img.get(headerLba * sector, sector, head.data()) ||
      std::memcmp(head.data(), "EFI PART", 8))
    return false;
  unsigned n = le32(head.data() + 12), crc = le32(head.data() + 16);
  if (n < 92 || n > sector)
    return false;
  std::fill(head.begin() + 16, head.begin() + 20, 0);
  if (crc32(0, head.data(), n) != crc || le64(head.data() + 24) != headerLba)
    return false;
  uint64_t first = le64(head.data() + 40), last = le64(head.data() + 48),
           table = le64(head.data() + 72);
  unsigned count = le32(head.data() + 80), stride = le32(head.data() + 84);
  if (first > last || last >= img.size / sector || stride < 128 ||
      stride % 128 || count > 16384 || stride > 4096)
    return false;
  uint64_t bytes = uint64_t(count) * stride;
  if (table >= img.size / sector || bytes > 16 * 1024 * 1024 ||
      bytes > img.size - table * sector)
    return false;
  std::vector<unsigned char> entries(static_cast<size_t>(bytes));
  if (!img.get(table * sector, entries.size(), entries.data()) ||
      crc32(0, entries.data(), entries.size()) != le32(head.data() + 88))
    return false;
  struct Part {
    uint64_t off, length;
    unsigned index;
  };
  std::vector<Part> parts;
  for (unsigned i = 0; i < count; i++) {
    auto *p = entries.data() + i * stride;
    bool used = false;
    for (unsigned j = 0; j < 16; j++)
      used |= p[j] != 0;
    if (!used)
      continue;
    uint64_t a = le64(p + 32), b = le64(p + 40);
    if (a < first || a > b || b > last)
      throw std::runtime_error("Invalid GPT partition boundaries");
    for (auto &old : parts)
      if (a * sector < old.off + old.length && old.off < (b + 1) * sector)
        throw std::runtime_error("Overlapping GPT partitions");
    parts.push_back({a * sector, (b - a + 1) * sector, i + 1});
  }
  for (auto &p : parts)
    addVolume(s, p.off, p.length, "GPT partition " + std::to_string(p.index));
  return true;
}
bool extended(unsigned t) { return t == 5 || t == 15 || t == 0x85; }
void scan(Session *s) {
  if (s->scanned)
    return;
  std::array<unsigned char, 512> mbr{};
  if (!s->image->get(0, mbr.size(), mbr.data()))
    throw std::runtime_error("Cannot read partition table");
  bool signature = mbr[510] == 0x55 && mbr[511] == 0xaa, protective = false;
  if (signature)
    for (int i = 0; i < 4; i++)
      protective |= mbr[446 + 16 * i + 4] == 0xee;
  if (protective) {
    bool good = false;
    for (unsigned sector : {s->image->sector, 512u, 4096u})
      if (gpt(s, sector, 1) || gpt(s, sector, s->image->size / sector - 1)) {
        good = true;
        break;
      }
    if (!good)
      throw std::runtime_error("GPT checksum or boundaries invalid");
  } else if (signature) {
    bool valid = true;
    int used = 0;
    unsigned sector = s->image->sector;
    for (int i = 0; i < 4; i++) {
      auto *p = mbr.data() + 446 + i * 16;
      if (!p[4])
        continue;
      used++;
      uint64_t a = le32(p + 8), n = le32(p + 12);
      if ((p[0] != 0 && p[0] != 0x80) || !a || !n ||
          a >= s->image->size / sector || n > s->image->size / sector - a)
        valid = false;
    }
    if (valid && used)
      for (int i = 0; i < 4; i++) {
        auto *p = mbr.data() + 446 + i * 16;
        if (!p[4])
          continue;
        uint64_t a = le32(p + 8), n = le32(p + 12);
        if (!extended(p[4]))
          addVolume(s, a * sector, n * sector,
                    "MBR partition " + std::to_string(i + 1));
        else {
          uint64_t ebr = a;
          std::unordered_set<uint64_t> seen;
          while (ebr) {
            if (seen.size() >= 128 || !seen.insert(ebr).second)
              throw std::runtime_error("Cyclic/oversized EBR chain");
            if (ebr < a || ebr >= a + n)
              throw std::runtime_error("EBR outside container");
            std::array<unsigned char, 512> e{};
            if (!s->image->get(ebr * sector, e.size(), e.data()) ||
                e[510] != 0x55 || e[511] != 0xaa)
              throw std::runtime_error("Invalid EBR");
            auto *q = e.data() + 446;
            uint64_t start = ebr + le32(q + 8), len = le32(q + 12);
            if (q[4] && len) {
              if (start < a || start >= a + n || len > a + n - start)
                throw std::runtime_error("Logical partition outside container");
              addVolume(s, start * sector, len * sector,
                        "MBR logical partition");
            }
            q += 16;
            if (!q[4])
              break;
            if (!extended(q[4]))
              throw std::runtime_error("Invalid EBR link");
            ebr = a + le32(q + 8);
          }
        }
      }
  }
  if (s->volumes.empty() && !s->image->error)
    addVolume(s, 0, s->image->size, "Whole disk / unpartitioned filesystem");
  s->scanned = true;
}
