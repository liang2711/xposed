#ifndef XPOSED_SERVICE_H_
#define XPOSED_SERVICE_H_

#include <sys/stat.h>
#include <unistd.h>

namespace xposed {
namespace service {
    bool startAll();

#if XPOSED_WITH_SELINUX
    bool startMembased();

    namespace membased {
        int accessFile(const char* path, int mode);
        int statFile(const char* path, struct stat* stat);
        char* readFile(const char* path, int* bytesRead);
        void restrictMemoryInheritance();
    }  // namespace membased
#endif  // XPOSED_WITH_SELINUX

}  // namespace service

static inline int zygote_access(const char *pathname, int mode) {
#if XPOSED_WITH_SELINUX
    if (xposed->isSELinuxEnabled)
        return xposed::service::membased::accessFile(pathname, mode);
#endif  // XPOSED_WITH_SELINUX
//access 函数是一个 POSIX 标准定义的系统调用函数，用于检查文件或目录的访问权限
    return access(pathname, mode);
}

}  // namespace xposed

#endif /* XPOSED_SERVICE_H_ */
