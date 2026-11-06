#pragma once
#include <cstdint>
#include <jni.h>
#include <string>
// NewStringUTF uses modified UTF-8 and corrupts supplementary characters (e.g.
// emoji).
jstring utf8(JNIEnv *env, const std::string &s) {
  std::u16string out;
  for (size_t i = 0; i < s.size();) {
    uint32_t c = static_cast<unsigned char>(s[i++]);
    unsigned n = c < 0x80 ? 0
                          : (c >= 0xc2 && c < 0xe0
                                 ? 1
                                 : (c < 0xf0 && c >= 0xe0
                                        ? 2
                                        : (c <= 0xf4 && c >= 0xf0 ? 3 : 4)));
    if (n == 4) {
      out.push_back(0xfffd);
      continue;
    }
    uint32_t value = n ? c & ((1u << (6 - n)) - 1) : c;
    bool valid = i + n <= s.size();
    for (unsigned j = 0; valid && j < n; j++) {
      unsigned char next = s[i + j];
      if ((next & 0xc0) != 0x80)
        valid = false;
      else
        value = (value << 6) | (next & 63);
    }
    if (valid && ((n == 1 && value < 0x80) || (n == 2 && value < 0x800) ||
                  (n == 3 && value < 0x10000) || value > 0x10ffff ||
                  (value >= 0xd800 && value <= 0xdfff)))
      valid = false;
    if (!valid) {
      out.push_back(0xfffd);
      continue;
    }
    i += n;
    if (value <= 0xffff)
      out.push_back(static_cast<char16_t>(value));
    else {
      value -= 0x10000;
      out.push_back(0xd800 + (value >> 10));
      out.push_back(0xdc00 + (value & 1023));
    }
  }
  return env->NewString(reinterpret_cast<const jchar *>(out.data()),
                        static_cast<jsize>(out.size()));
}
void fail(JNIEnv *env, const std::string &message) {
  if (env->ExceptionCheck())
    return;
  jclass cls = env->FindClass("java/io/IOException");
  if (!cls)
    return;
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;)V");
  jstring msg = utf8(env, message);
  if (ctor && msg) {
    auto exception = static_cast<jthrowable>(env->NewObject(cls, ctor, msg));
    if (exception) {
      env->Throw(exception);
      env->DeleteLocalRef(exception);
    }
  }
  env->DeleteLocalRef(msg);
  env->DeleteLocalRef(cls);
}
