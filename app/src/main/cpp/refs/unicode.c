#include "sys.h"
int sys_unistr_encode(const char *in, size_t n, refschar **out,
                      size_t *out_len) {
  if (!out || !out_len || n > (SIZE_MAX / sizeof(refschar)) - 1)
    return EINVAL;
  refschar *p = malloc((n + 1) * sizeof(*p));
  if (!p)
    return ENOMEM;
  size_t used = 0;
  for (size_t i = 0; i < n;) {
    unsigned char a = (unsigned char)in[i++];
    unsigned count = a < 128 ? 0
                             : (a >= 0xc2 && a < 0xe0
                                    ? 1
                                    : (a >= 0xe0 && a < 0xf0
                                           ? 2
                                           : (a >= 0xf0 && a <= 0xf4 ? 3 : 4)));
    if (count == 4 || n - i < count) {
      free(p);
      return EILSEQ;
    }
    uint32_t c = count ? a & ((1u << (6 - count)) - 1) : a;
    for (unsigned j = 0; j < count; j++) {
      unsigned char b = (unsigned char)in[i++];
      if ((b & 0xc0) != 0x80) {
        free(p);
        return EILSEQ;
      }
      c = (c << 6) | (b & 63);
    }
    if ((count == 1 && c < 128) || (count == 2 && c < 2048) ||
        (count == 3 && c < 65536) || c > 0x10ffff ||
        (c >= 0xd800 && c <= 0xdfff)) {
      free(p);
      return EILSEQ;
    }
    if (c < 65536)
      p[used++] = (refschar)c;
    else {
      c -= 65536;
      p[used++] = (refschar)(0xd800 + (c >> 10));
      p[used++] = (refschar)(0xdc00 + (c & 1023));
    }
  }
  p[used] = 0;
  if (*out) {
    if (used >= *out_len) {
      free(p);
      return ERANGE;
    }
    memcpy(*out, p, (used + 1) * sizeof(*p));
    free(p);
  } else
    *out = p;
  *out_len = used;
  return 0;
}
