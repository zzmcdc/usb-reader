/* ReFS reads from the same bounded USB callback as every other filesystem. */
int usbreader_refs_read(void *dev, u64 offset, size_t size, void *buffer);
int usbreader_refs_size(void *dev, u64 *size);
int usbreader_refs_sector(void *dev, u32 *sector);
static inline int sys_device_pread(sys_device *dev, u64 offset, size_t size, void *buffer) {
    return usbreader_refs_read(dev, offset, size, buffer);
}
static inline int sys_device_get_size(sys_device *dev, u64 *size) { return usbreader_refs_size(dev, size); }
static inline int sys_device_get_sector_size(sys_device *dev, u32 *sector) { return usbreader_refs_sector(dev, sector); }
