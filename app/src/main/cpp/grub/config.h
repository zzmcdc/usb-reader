#pragma once
#define GRUB_KERNEL 1
#ifdef __cplusplus
#define restrict __restrict
#define typeof __typeof__
#define GRUB_LST_GENERATOR 1
#endif
#define GRUB_FILE "filesystem"
#define GRUB_TARGET_SIZEOF_VOID_P __SIZEOF_POINTER__
#define GRUB_TARGET_SIZEOF_LONG __SIZEOF_LONG__
#define HAVE_SYS_TYPES_H 1
#define HAVE_STDINT_H 1
#define HAVE_LIMITS_H 1
#define HAVE_BUILTIN_MUL_OVERFLOW 1
#define HAVE_BUILTIN_ADD_OVERFLOW 1
#define HAVE_BUILTIN_SUB_OVERFLOW 1
#define PACKAGE "usbreader"
#define PACKAGE_VERSION "2.0"
#define ENABLE_NLS 0
