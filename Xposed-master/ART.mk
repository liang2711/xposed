##########################################################
# Library for ART-specific functions
##########################################################
#清除之前的变量定义
include $(CLEAR_VARS)

#包含art/build/Android.common_build.mk文件这个文件就是xposed作者的修改的art
include art/build/Android.common_build.mk
#调用set-target-local-clang-vars函数。
$(eval $(call set-target-local-clang-vars))
#调用set-target-local-cflags-vars函数，并传入参数ndebug。
$(eval $(call set-target-local-cflags-vars,ndebug))
#PLATFORM_SDK_VERSION是否大于23
ifeq (1,$(strip $(shell expr $(PLATFORM_SDK_VERSION) \>= 23)))
    #添加编译路径
  LOCAL_C_INCLUDES += \
    external/valgrind \
    external/valgrind/include
else
  include external/libcxx/libcxx.mk
  LOCAL_C_INCLUDES += \
    external/valgrind/main \
    external/valgrind/main/include
endif
#编译的文件
LOCAL_SRC_FILES += \
  libxposed_common.cpp \
  libxposed_art.cpp

LOCAL_C_INCLUDES += \
  art/runtime \
  external/gtest/include

ifeq (1,$(strip $(shell expr $(PLATFORM_SDK_VERSION) \>= 24)))
  LOCAL_C_INCLUDES += bionic/libc/private
endif
#编译的动态库
LOCAL_SHARED_LIBRARIES += \
  libart \
  liblog \
  libcutils \
  libandroidfw \
  libnativehelper
#编译时选项
LOCAL_CFLAGS += \
  -DPLATFORM_SDK_VERSION=$(PLATFORM_SDK_VERSION) \
  -DXPOSED_WITH_SELINUX=1

#指定目标库的名称
LOCAL_MODULE := libxposed_art
#指定库的标签
LOCAL_MODULE_TAGS := optional
#指定在构建过程中保留目标的符号信息
LOCAL_STRIP_MODULE := keep_symbols
LOCAL_MULTILIB := both

# Always build both architectures (if applicable)
ifeq ($(TARGET_IS_64_BIT),true)
  $(LOCAL_MODULE): $(LOCAL_MODULE)$(TARGET_2ND_ARCH_MODULE_SUFFIX)
endif

include $(BUILD_SHARED_LIBRARY)
