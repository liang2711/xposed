/**
 * This file includes functions specific to the ART runtime.
 */

#define LOG_TAG "Xposed"

#include "xposed_shared.h"
#include "libxposed_common.h"
#if PLATFORM_SDK_VERSION >= 21
#include "fd_utils-inl.h"
#endif

#include "thread.h"
#include "common_throws.h"
#if PLATFORM_SDK_VERSION >= 23
#include "art_method-inl.h"
#else
#include "mirror/art_method-inl.h"
#endif
#include "mirror/object-inl.h"
#include "mirror/throwable.h"
#include "native/scoped_fast_native_object_access.h"
#include "reflection.h"
#include "scoped_thread_state_change.h"
#include "well_known_classes.h"

#if PLATFORM_SDK_VERSION >= 24
#include "mirror/abstract_method.h"
#include "thread_list.h"
#endif

using namespace art;

#if PLATFORM_SDK_VERSION < 23
/*
    art::mirror::Object。
    在art中命名空间有"mirror"，表示其和Java类之间存在对应关系。
    当我们通过new Object()来创建一个java对象时，就会在内存空间得到一个最简单的内存结构
*/
using art::mirror::ArtMethod;
#endif

namespace xposed {


////////////////////////////////////////////////////////////
// Library initialization
////////////////////////////////////////////////////////////

/** Called by Xposed's app_process replacement. */
bool xposedInitLib(XposedShared* shared) {
    xposed = shared;
    //在之前调用的xposed::onVmCreated就是在调用onVmCreatedCommon;
    //common.cpp onVmCreatedCommon调用下面的onVmCreated(JNIEnv*)并进行了一些初始化
    xposed->onVmCreated = &onVmCreatedCommon;
    return true;
}

/** Called very early during VM startup. */
bool onVmCreated(JNIEnv*) {
    // TODO: Handle CLASS_MIUI_RESOURCES?
    //可以再ArtMethod中添加自己的静态变量
    ArtMethod::xposed_callback_class = classXposedBridge;
    ArtMethod::xposed_callback_method = methodXposedBridgeHandleHookedMethod;
    return true;
}


////////////////////////////////////////////////////////////
// Utility methods
////////////////////////////////////////////////////////////
void logExceptionStackTrace() {
    Thread* self = Thread::Current();
    //用于确保在当前作用域内持有线程的对象锁，以便安全地访问对象。
    ScopedObjectAccess soa(self);
#if PLATFORM_SDK_VERSION >= 23
    XLOG(ERROR) << self->GetException()->Dump();
#else
    XLOG(ERROR) << self->GetException(nullptr)->Dump();
#endif
}

////////////////////////////////////////////////////////////
// JNI methods
////////////////////////////////////////////////////////////

void XposedBridge_hookMethodNative(JNIEnv* env, jclass, jobject javaReflectedMethod,
            jobject, jint, jobject javaAdditionalInfo) {
    // Detect usage errors.
    //用于确保在当前作用域内持有线程的对象锁，以便安全地访问对象。
    // 判断 env指针是否正确，有检查的功能
    //(SOA，就是约定的调用，包装env，出了函数范围自动释放)
    ScopedObjectAccess soa(env);
    if (javaReflectedMethod == nullptr) {
#if PLATFORM_SDK_VERSION >= 23
        ThrowIllegalArgumentException("method must not be null");
#else
        ThrowIllegalArgumentException(nullptr, "method must not be null");
#endif
        return;
    }

    // Get the ArtMethod of the method to be hooked.
    //javaReflectedMethod是被Hook方法的Method 根据Method 返回ArtMethod
    ArtMethod* artMethod = ArtMethod::FromReflectedMethod(soa, javaReflectedMethod);

    // Hook the method
    /* 这个真正的是对定义的函数进行hook，这是xpose作者修改的artmethod，
        在install刷机的shell文件，就把作者改动的art动态库的替换了
    */
    artMethod->EnableXposedHook(soa, javaAdditionalInfo);
}

jobject XposedBridge_invokeOriginalMethodNative(JNIEnv* env, jclass, jobject javaMethod,
            jint isResolved, jobjectArray, jclass, jobject javaReceiver, jobjectArray javaArgs) {
    ScopedFastNativeObjectAccess soa(env);
    if (UNLIKELY(!isResolved)) {
        ArtMethod* artMethod = ArtMethod::FromReflectedMethod(soa, javaMethod);
        //是否被当前的artmethod hook
        if (LIKELY(artMethod->IsXposedHookedMethod())) {
            //得到Java mirror的函数
            javaMethod = artMethod->GetXposedHookInfo()->reflected_method;
        }
    }
#if PLATFORM_SDK_VERSION >= 23
    return InvokeMethod(soa, javaMethod, javaReceiver, javaArgs);
#else
    return InvokeMethod(soa, javaMethod, javaReceiver, javaArgs, true);
#endif
}

void XposedBridge_setObjectClassNative(JNIEnv* env, jclass, jobject javaObj, jclass javaClazz) {
    ScopedObjectAccess soa(env);
    StackHandleScope<3> hs(soa.Self());
    //将javaClazz转换为mirror::Class类型的句柄（Handle<mirror::Class>）
    Handle<mirror::Class> clazz(hs.NewHandle(soa.Decode<mirror::Class*>(javaClazz)));
#if PLATFORM_SDK_VERSION >= 23
    if (!Runtime::Current()->GetClassLinker()->EnsureInitialized(soa.Self(), clazz, true, true)) {
#else
    if (!Runtime::Current()->GetClassLinker()->EnsureInitialized(clazz, true, true)) {
#endif
        XLOG(ERROR) << "Could not initialize class " << PrettyClass(clazz.Get());
        return;
    }
    //转换
    Handle<mirror::Object> obj(hs.NewHandle(soa.Decode<mirror::Object*>(javaObj)));
    Handle<mirror::Class> currentClass(hs.NewHandle(obj->GetClass()));
    if (clazz->GetObjectSize() != currentClass->GetObjectSize()) {
        std::string msg = StringPrintf("Different object sizes: %s (%d) vs. %s (%d)",
                PrettyClass(clazz.Get()).c_str(), clazz->GetObjectSize(),
                PrettyClass(currentClass.Get()).c_str(), currentClass->GetObjectSize());
#if PLATFORM_SDK_VERSION >= 23
        ThrowIllegalArgumentException(msg.c_str());
#else
        ThrowIllegalArgumentException(nullptr, msg.c_str());
#endif
        return;
    }
    obj->SetClass(clazz.Get());
}

void XposedBridge_dumpObjectNative(JNIEnv*, jclass, jobject) {
    // TODO Can be useful for debugging
    UNIMPLEMENTED(ERROR|LOG_XPOSED);
}

jobject XposedBridge_cloneToSubclassNative(JNIEnv* env, jclass, jobject javaObject, jclass javaClazz) {
    ScopedObjectAccess soa(env);
    StackHandleScope<3> hs(soa.Self());
    Handle<mirror::Object> obj(hs.NewHandle(soa.Decode<mirror::Object*>(javaObject)));
    Handle<mirror::Class> clazz(hs.NewHandle(soa.Decode<mirror::Class*>(javaClazz)));
    Handle<mirror::Object> dest(hs.NewHandle(obj->Clone(soa.Self(), clazz.Get())));
    return soa.AddLocalReference<jobject>(dest.Get());
}

void XposedBridge_removeFinalFlagNative(JNIEnv* env, jclass, jclass javaClazz) {
    ScopedObjectAccess soa(env);
    StackHandleScope<1> hs(soa.Self());
    Handle<mirror::Class> clazz(hs.NewHandle(soa.Decode<mirror::Class*>(javaClazz)));
    uint32_t flags = clazz->GetAccessFlags();
    if ((flags & kAccFinal) != 0) {
        clazz->SetAccessFlags(flags & ~kAccFinal);
    }
}

jint XposedBridge_getRuntime(JNIEnv*, jclass) {
    return 2; // RUNTIME_ART
}

#if PLATFORM_SDK_VERSION >= 21
static FileDescriptorTable* gClosedFdTable = NULL;

void XposedBridge_closeFilesBeforeForkNative(JNIEnv*, jclass) {
    gClosedFdTable = FileDescriptorTable::Create();
}

void XposedBridge_reopenFilesAfterForkNative(JNIEnv*, jclass) {
    gClosedFdTable->Reopen();
    delete gClosedFdTable;
    gClosedFdTable = NULL;
}
#endif

#if PLATFORM_SDK_VERSION >= 24
void XposedBridge_invalidateCallersNative(JNIEnv* env, jclass, jobjectArray javaMethods) {
    ScopedObjectAccess soa(env);
    auto* runtime = Runtime::Current();
    auto* cl = runtime->GetClassLinker();

    // Invalidate callers of the given methods.
    auto* abstract_methods = soa.Decode<mirror::ObjectArray<mirror::AbstractMethod>*>(javaMethods);
    size_t count = abstract_methods->GetLength();
    for (size_t i = 0; i < count; i++) {
        auto* abstract_method = abstract_methods->Get(i);
        if (abstract_method == nullptr) {
            continue;
        }
        ArtMethod* method = abstract_method->GetArtMethod();
        cl->InvalidateCallersForMethod(soa.Self(), method);
    }

    // Now instrument the stack to deoptimize methods which are being called right now.
    ScopedThreadSuspension sts(soa.Self(), kSuspended);
    ScopedSuspendAll ssa(__FUNCTION__);
    MutexLock mu(soa.Self(), *Locks::thread_list_lock_);
    runtime->GetThreadList()->ForEach([](Thread* thread, void*) SHARED_REQUIRES(Locks::mutator_lock_) {
        Runtime::Current()->GetInstrumentation()->InstrumentThreadStack(thread);
    }, nullptr);
}
#endif

}  // namespace xposed
