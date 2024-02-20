/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
//<string>：该头文件提供了字符串处理相关的函数和类，包括字符串操作、搜索、替换等功能。
#include <string>
//<unordered_map>：该头文件定义了无序映射容器 unordered_map，它以键-值对的形式存储数据，并提供了快速的查找和插入操作。
#include <unordered_map>
//<set>：该头文件定义了集合容器 set，它存储一组唯一的元素，并按照一定的顺序进行排序。
#include <set>
//<vector>：该头文件定义了动态数组容器 vector，它可以自动调整大小，存储任意类型的元素，并提供了快速的随机访问。
#include <vector>
//<algorithm>：该头文件提供了常见的算法函数，如排序、查找、合并等，用于操作容器中的数据
#include <algorithm>

//该头文件提供了对目录和文件的访问函数和结构体定义，如打开、读取和关闭目录等
#include <dirent.h>
//该头文件定义了文件控制函数和常量，用于打开、关闭、读取和写入文件，以及设置文件属性等
#include <fcntl.h>
//该头文件定义了与用户组相关的函数和结构体定义，如获取用户组信息、检查用户组成员等
#include <grp.h>
//该头文件定义了整数类型的宏定义，用于跨平台的整数处理。
#include <inttypes.h>
//该头文件提供了一些通用的函数和类型定义，如内存管理、字符串转换等。
#include <stdlib.h>
//该头文件定义了与套接字及网络通信相关的函数和结构体定义，用于网络编程。
#include <sys/socket.h>
//该头文件定义了与文件状态相关的函数和结构体定义，如获取文件信息、修改文件权限等。
#include <sys/stat.h>
//该头文件定义了系统级的基本数据类型和结构体，如整数类型、文件描述符类型等。
#include <sys/types.h>
//该头文件定义了与Unix域套接字相关的函数和结构体定义，用于本地进程间通信。
#include <sys/un.h>
//该头文件定义了与POSIX标准相关的函数和类型定义，如进程控制、文件操作等。
#include <unistd.h>

//该头文件提供了Android系统中的日志函数和宏定义，用于在应用程序中输出日志信息。
#include <cutils/log.h>
#include "JNIHelp.h"
#include "ScopedPrimitiveArray.h"

static const char* kPathPrefixWhitelist[] = {
  "/data/app/",
  "/data/app-private/",
  "/system/app/",
  "/system/priv-app/",
  "/vendor/app/",
  "/vendor/priv-app/",
};

/*
如果 /proc/self/fd/0 是一个符号链接，指向了 /dev/pts/0，
那么这意味着文件描述符 0 当前指向了 /dev/pts/0 这个终端设备文件。
*/
static const char* kFdPath = "/proc/self/fd";

// Keeps track of all relevant information (flags, offset etc.) of an
// open zygote file descriptor.
class FileDescriptorInfo {
 public:
  // Create a FileDescriptorInfo for a given file descriptor. Returns
  // |NULL| if an error occurred.
  static FileDescriptorInfo* createFromFd(int fd) {
    struct stat f_stat;
    // This should never happen; the zygote should always have the right set
    // of permissions required to stat all its open files.
    /*
    fstat(fd, &f_stat) 是一个系统调用，用于获取文件描述符 fd 所关联的文件的状态信息。
    &f_stat 是一个指向 struct stat 结构体的指针，用于存储文件状态信息。

    使用 TEMP_FAILURE_RETRY 宏对 fstat 系统调用进行包装，
    可以确保在系统调用被中断时自动进行重试，以提高系统调用的可靠性
    */
    if (TEMP_FAILURE_RETRY(fstat(fd, &f_stat)) == -1) {
      ALOGE("Unable to stat fd %d : %s", fd, strerror(errno));
      return NULL;
    }

    //查看f_stat是否是个socket文件
    if (S_ISSOCK(f_stat.st_mode)) {
      std::string socket_name;
      //获得socket的地址
      if (!GetSocketName(fd, &socket_name)) {
        return NULL;
      }
       //用于检查给定的套接字名称（socket_name）是否在白名单中 是否在kPathPrefixWhitelist中
      if (!IsWhitelisted(socket_name)) {
        //ALOGE("Socket name not whitelisted : %s (fd=%d)", socket_name.c_str(), fd);
        return NULL;
      }

      return new FileDescriptorInfo(fd);
    }

    std::string file_path;
    //获得文件符号路径的目的地
    if (!Readlink(fd, &file_path)) {
      return NULL;
    }

    if (!IsWhitelisted(file_path)) {
      //ALOGE("Not whitelisted : %s", file_path.c_str());
      return NULL;
    }

    // We only handle whitelisted regular files and character devices. Whitelisted
    // character devices must provide a guarantee of sensible behaviour when
    // reopened.
    //
    // S_ISDIR : Not supported. (We could if we wanted to, but it's unused).
    // S_ISLINK : Not supported.
    // S_ISBLK : Not supported.
    // S_ISFIFO : Not supported. Note that the zygote uses pipes to communicate
    // with the child process across forks but those should have been closed
    // before we got to this point.
    //是否是个字符设备，是否是个常规文件
    if (!S_ISCHR(f_stat.st_mode) && !S_ISREG(f_stat.st_mode)) {
      ALOGE("Unsupported st_mode %d", f_stat.st_mode);
      return NULL;
    }

    // File descriptor flags : currently on FD_CLOEXEC. We can set these
    // using F_SETFD - we're single threaded at this point of execution so
    // there won't be any races.
    //获得fd的close_on_exec
    const int fd_flags = TEMP_FAILURE_RETRY(fcntl(fd, F_GETFD));
    if (fd_flags == -1) {
      ALOGE("Failed fcntl(%d, F_GETFD) : %s", fd, strerror(errno));
      return NULL;
    }

    // File status flags :
    // - File access mode : (O_RDONLY, O_WRONLY...) we'll pass these through
    //   to the open() call.
    //
    // - File creation flags : (O_CREAT, O_EXCL...) - there's not much we can
    //   do about these, since the file has already been created. We shall ignore
    //   them here.
    //
    // - Other flags : We'll have to set these via F_SETFL. On linux, F_SETFL
    //   can only set O_APPEND, O_ASYNC, O_DIRECT, O_NOATIME, and O_NONBLOCK.
    //   In particular, it can't set O_SYNC and O_DSYNC. We'll have to test for
    //   their presence and pass them in to open().
    //获取fd指向文件的状态，这个文件是只读还是读写都可以
    int fs_flags = TEMP_FAILURE_RETRY(fcntl(fd, F_GETFL));
    if (fs_flags == -1) {
      ALOGE("Failed fcntl(%d, F_GETFL) : %s", fd, strerror(errno));
      return NULL;
    }

    // File offset : Ignore the offset for non seekable files.5
    //获取fd指向的文件偏移量，这个偏移量是指读取文件中的位置，一般开始是0。
    const off_t offset = TEMP_FAILURE_RETRY(lseek64(fd, 0, SEEK_CUR));

    // We pass the flags that open accepts to open, and use F_SETFL for
    // the rest of them.
    static const int kOpenFlags = (O_RDONLY | O_WRONLY | O_RDWR | O_DSYNC | O_SYNC);
    int open_flags = fs_flags & (kOpenFlags);
    fs_flags = fs_flags & (~(kOpenFlags));

    return new FileDescriptorInfo(f_stat, file_path, fd, open_flags, fd_flags, fs_flags, offset);
  }
//常量函数，里面的成员变量不能改 重定向fd为null
  bool Detach() const {
    const int dev_null_fd = open("/dev/null", O_RDWR);
    if (dev_null_fd < 0) {
      ALOGE("Failed to open /dev/null : %s", strerror(errno));
      return false;
    }

    if (dup2(dev_null_fd, fd) == -1) {
      ALOGE("Failed dup2 on socket descriptor %d : %s", fd, strerror(errno));
      return false;
    }

    if (close(dev_null_fd) == -1) {
      ALOGE("Failed close(%d) : %s", dev_null_fd, strerror(errno));
      return false;
    }

    return true;
  }
   //刷新
  bool Reopen() const {
    if (is_sock) {
      return true;
    }

    // NOTE: This might happen if the file was unlinked after being opened.
    // It's a common pattern in the case of temporary files and the like but
    // we should not allow such usage from the zygote.
    //c_str()将数组转为c格式的数组/0加在后面      获得file_path的文件的文件描述符
    const int new_fd = TEMP_FAILURE_RETRY(open(file_path.c_str(), open_flags));

    if (new_fd == -1) {
      ALOGE("Failed open(%s, %d) : %s", file_path.c_str(), open_flags, strerror(errno));
      return false;
    }

    if (TEMP_FAILURE_RETRY(fcntl(new_fd, F_SETFD, fd_flags)) == -1) {
      close(new_fd);
      ALOGE("Failed fcntl(%d, F_SETFD, %x) : %s", new_fd, fd_flags, strerror(errno));
      return false;
    }

    if (TEMP_FAILURE_RETRY(fcntl(new_fd, F_SETFL, fs_flags)) == -1) {
      close(new_fd);
      ALOGE("Failed fcntl(%d, F_SETFL, %x) : %s", new_fd, fs_flags, strerror(errno));
      return false;
    }

    if (offset != -1 && TEMP_FAILURE_RETRY(lseek64(new_fd, offset, SEEK_SET)) == -1) {
      close(new_fd);
      ALOGE("Failed lseek64(%d, SEEK_SET) : %s", new_fd, strerror(errno));
      return false;
    }

    if (TEMP_FAILURE_RETRY(dup2(new_fd, fd)) == -1) {
      close(new_fd);
      ALOGE("Failed dup2(%d, %d) : %s", fd, new_fd, strerror(errno));
      return false;
    }

    close(new_fd);

    return true;
  }

  const int fd;
  const struct stat stat;
  const std::string file_path;
  const int open_flags;
  const int fd_flags;
  const int fs_flags;
  const off_t offset;
  const bool is_sock;

 private:
  FileDescriptorInfo(int pfd) :
    fd(pfd),
    stat(),
    open_flags(0),
    fd_flags(0),
    fs_flags(0),
    offset(0),
    is_sock(true) {
  }

  FileDescriptorInfo(struct stat pstat, const std::string& pfile_path, int pfd, int popen_flags,
                     int pfd_flags, int pfs_flags, off_t poffset) :
    fd(pfd),
    stat(pstat),
    file_path(pfile_path),
    open_flags(popen_flags),
    fd_flags(pfd_flags),
    fs_flags(pfs_flags),
    offset(poffset),
    is_sock(false) {
  }

  // Returns true iff. a given path is whitelisted.
  static bool IsWhitelisted(const std::string& path) {
    for (size_t i = 0; i < (sizeof(kPathPrefixWhitelist) / sizeof(kPathPrefixWhitelist[0])); ++i) {
      if (path.compare(0, strlen(kPathPrefixWhitelist[i]), kPathPrefixWhitelist[i]) == 0) {
        return true;
      }
    }
    return false;
  }

  // TODO: Call android::base::Readlink instead of copying the code here.
  static bool Readlink(const int fd, std::string* result) {
    char path[64];
    //将"/proc/self/fd/%d"写入path里
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);

    // Code copied from android::base::Readlink starts here :

    // Annoyingly, the readlink system call returns EINVAL for a zero-sized buffer,
    // and truncates to whatever size you do supply, so it can't be used to query.
    // We could call lstat first, but that would introduce a race condition that
    // we couldn't detect.
    // ext2 and ext4 both have PAGE_SIZE limitations, so we assume that here.
    char* buf = new char[4096];
    //获得path的符号链接文件的目标路径
    ssize_t len = readlink(path, buf, 4096);
    if (len == -1) {
      delete[] buf;
      return false;
    }
    //将buf获取的内容给result
    result->assign(buf, len);
    delete[] buf;
    return true;
  }

  // Returns the locally-bound name of the socket |fd|. Returns true
  // iff. all of the following hold :
  //
  // - the socket's sa_family is AF_UNIX.
  // - the length of the path is greater than zero (i.e, not an unnamed socket).
  // - the first byte of the path isn't zero (i.e, not a socket with an abstract
  //   address).
  static bool GetSocketName(const int fd, std::string* result) {
    sockaddr_storage ss;
    sockaddr* addr = reinterpret_cast<sockaddr*>(&ss);
    socklen_t addr_len = sizeof(ss);

    if (TEMP_FAILURE_RETRY(getsockname(fd, addr, &addr_len)) == -1) {
      ALOGE("Failed getsockname(%d) : %s", fd, strerror(errno));
      return false;
    }

#if PLATFORM_SDK_VERSION <= 23
    if (addr->sa_family == AF_NETLINK) {
      (*result) = "@netlink@";
      return true;
    }
#endif

    if (addr->sa_family != AF_UNIX) {
      //ALOGE("Unsupported socket (fd=%d) with family %d", fd, addr->sa_family);
      return false;
    }

    const sockaddr_un* unix_addr = reinterpret_cast<const sockaddr_un*>(&ss);

    size_t path_len = addr_len - offsetof(struct sockaddr_un, sun_path);
    // This is an unnamed local socket, we do not accept it.
    if (path_len == 0) {
      //ALOGE("Unsupported AF_UNIX socket (fd=%d) with empty path.", fd);
      return false;
    }

    // This is a local socket with an abstract address, we do not accept it.
    if (unix_addr->sun_path[0] == '\0') {
      //ALOGE("Unsupported AF_UNIX socket (fd=%d) with abstract address.", fd);
      return false;
    }

    // If we're here, sun_path must refer to a null terminated filesystem
    // pathname (man 7 unix). Remove the terminator before assigning it to an
    // std::string.
    if (unix_addr->sun_path[path_len - 1] ==  '\0') {
      --path_len;
    }

    result->assign(unix_addr->sun_path, path_len);
    return true;
  }


  // DISALLOW_COPY_AND_ASSIGN(FileDescriptorInfo);
  FileDescriptorInfo(const FileDescriptorInfo&);
  void operator=(const FileDescriptorInfo&);
};

// A FileDescriptorTable is a collection of FileDescriptorInfo objects
// keyed by their FDs.
class FileDescriptorTable {
 public:
  // Creates a new FileDescriptorTable. This function scans
  // /proc/self/fd for the list of open file descriptors and collects
  // information about them. Returns NULL if an error occurs.
  static FileDescriptorTable* Create() {
    DIR* d = opendir(kFdPath);
    if (d == NULL) {
      ALOGE("Unable to open directory %s: %s", kFdPath, strerror(errno));
      return NULL;
    }
    //获得目录的文件描述符
    int dir_fd = dirfd(d);
    // dirent 结构体的指针，该结构体包含了目录中下一个文件或子目录的信息
    dirent* e;

    std::unordered_map<int, FileDescriptorInfo*> open_fd_map;
    //获得包含在/proc/self/fd中的文件描述符
    while ((e = readdir(d)) != NULL) {
      const int fd = ParseFd(e, dir_fd);
      if (fd == -1) {
        continue;
      }

      FileDescriptorInfo* info = FileDescriptorInfo::createFromFd(fd);
      if (info == NULL) {
        continue;
      }
      //把fd清除文件描述符，但在info有文件描述符的地址
      info->Detach();
      open_fd_map[fd] = info;
    }

    if (closedir(d) == -1) {
      ALOGE("Unable to close directory : %s", strerror(errno));
      return NULL;
    }
    return new FileDescriptorTable(open_fd_map);
  }

  // Reopens all file descriptors that are contained in the table.重新打开表中包含的所有文件描述符。
  void Reopen() {
    std::unordered_map<int, FileDescriptorInfo*>::const_iterator it;
    for (it = open_fd_map_.begin(); it != open_fd_map_.end(); ++it) {
      const FileDescriptorInfo* info = it->second;
      if (info != NULL) {
        info->Reopen();
        delete info;
      }
    }
  }

 private:
  FileDescriptorTable(const std::unordered_map<int, FileDescriptorInfo*>& map)
      : open_fd_map_(map) {
  }

  static int ParseFd(dirent* e, int dir_fd) {
    char* end;
    //将字符串转换为长整型数 如果转换不成功的会在end上，当end只有\0时说明全部转换成功
    const int fd = strtol(e->d_name, &end, 10);
    if ((*end) != '\0') {
      return -1;
    }

    // Don't bother with the standard input/output/error, they're handled
    // specially post-fork anyway.
    if (fd <= STDERR_FILENO || fd == dir_fd) {
      return -1;
    }

    return fd;
  }

  // Invariant: All values in this unordered_map are non-NULL.
  std::unordered_map<int, FileDescriptorInfo*> open_fd_map_;

  // DISALLOW_COPY_AND_ASSIGN(FileDescriptorTable);
  FileDescriptorTable(const FileDescriptorTable&);
  void operator=(const FileDescriptorTable&);
};
