##########################################################
# Customized app_process executable
# Android.mk 文件用于定义 Application.mk、构建系统和环境变量所未定义的项目级设置
##########################################################
#每个 Android.mk 文件必须在文件头部最开始处定义 LOCAL_PATH 变量，该变量用来获取工程中的文件节点
#此变量用于指定当前文件的路径
LOCAL_PATH:= $(call my-dir)
#CLEAR_VARS 变量是构建系统提供
#此变量指向的构建脚本用于取消定义下文“开发者定义的变量”部分中列出的几乎所有 LOCAL_XXX 变量。
include $(CLEAR_VARS)


ifeq (1,$(strip $(shell expr $(PLATFORM_SDK_VERSION) \>= 21)))
#变量LOCAL_SRC_FILES 是用来定义将要生成的目标动态库所需要的源码文件列表
  LOCAL_SRC_FILES := app_main2.cpp
  LOCAL_MULTILIB := both
  LOCAL_MODULE_STEM_32 := app_process32_xposed
  LOCAL_MODULE_STEM_64 := app_process64_xposed
else
  LOCAL_SRC_FILES := app_main.cpp
  LOCAL_MODULE_STEM := app_process_xposed
endif

#加上这些文件
LOCAL_SRC_FILES += \
  xposed.cpp \
  xposed_logcat.cpp \
  xposed_service.cpp \
  xposed_safemode.cpp

#要连接到本模块的动态库
LOCAL_SHARED_LIBRARIES := \
  libcutils \
  libutils \
  liblog \
  libbinder \
  libandroid_runtime \
  libdl

#在编译C/C++ source 时添加如Flags，用来附加编译选项
LOCAL_CFLAGS += -Wall -Werror -Wextra -Wunused
LOCAL_CFLAGS += -DPLATFORM_SDK_VERSION=$(PLATFORM_SDK_VERSION)

ifeq (1,$(strip $(shell expr $(PLATFORM_SDK_VERSION) \>= 17)))
  LOCAL_SHARED_LIBRARIES += libselinux
  LOCAL_CFLAGS += -DXPOSED_WITH_SELINUX=1
endif

ifeq (1,$(strip $(shell expr $(PLATFORM_SDK_VERSION) \>= 22)))
  LOCAL_WHOLE_STATIC_LIBRARIES := libsigchain
  LOCAL_LDFLAGS := -Wl,--version-script,art/sigchainlib/version-script.txt -Wl,--export-dynamic
endif

ifeq (1,$(strip $(shell expr $(PLATFORM_SDK_VERSION) \>= 23)))
  LOCAL_SHARED_LIBRARIES += libwilhelm
endif

#变量是用来声明需要被生成的 module 名称
LOCAL_MODULE := xposed
LOCAL_MODULE_TAGS := optional
LOCAL_STRIP_MODULE := keep_symbols

# Always build both architectures (if applicable)
ifeq ($(TARGET_IS_64_BIT),true)
  $(LOCAL_MODULE): $(LOCAL_MODULE)$(TARGET_2ND_ARCH_MODULE_SUFFIX)
endif

#此变量指向的构建脚本会收集您在 LOCAL_XXX 变量中提供的模块的所有相关信息，
#以及确定如何根据您列出的源文件构建目标可执行文件 C可执行程序（这整个编译就是为了创建可替换的app_process）
include $(BUILD_EXECUTABLE)

##########################################################
# Library for Dalvik-/ART-specific functions 判断当前是那个版本去配置适应版本的虚拟机
#下面include会被编译为.so
##########################################################
ifeq (1,$(strip $(shell expr $(PLATFORM_SDK_VERSION) \>= 21)))
  include frameworks/base/cmds/xposed/ART.mk
else
  include frameworks/base/cmds/xposed/Dalvik.mk
endif
