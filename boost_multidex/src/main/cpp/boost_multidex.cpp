//
// Created by Xiaolin(xiaolin.gan@bytedance.com) on 2019/3/5.
//
#include <jni.h>
#include <zlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <sys/mman.h>

#include <cstdio>
#include <android/log.h>
#include <dlfcn.h>
#include <malloc.h>
#include <memory.h>

#include <sys/system_properties.h>
#include <fstream>
#include <csetjmp>

#define LOG_TAG "BOOST_MULTIDEX.NATIVE"

static constexpr char cond = false;

#define ALOGF(format, ...) __android_log_assert(&cond, LOG_TAG, format, ##__VA_ARGS__)

#define ALOGE(format, ...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, format, ##__VA_ARGS__)
#define ALOGW(format, ...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, format, ##__VA_ARGS__)
#define ALOGI(format, ...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, format, ##__VA_ARGS__)

#ifndef NDEBUG
#define NDEBUG // remove log
#endif

#ifdef NDEBUG
//#define ALOGD(format, ...)
//#define ALOGV(format, ...)
#define ALOGD(format, ...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, format, ##__VA_ARGS__)
#define ALOGV(format, ...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, format, ##__VA_ARGS__)
#else
#define ALOGD(format, ...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, format, ##__VA_ARGS__)
#define ALOGV(format, ...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, format, ##__VA_ARGS__)
#endif

struct Object {
    void*    clazz;
    uint32_t lock;
};

struct ArrayObject : Object {
    uint32_t              length;
    uint64_t              contents[1];
};

using func_openDexFileBytes = void (*)(const uint32_t* args, int32_t* pResult);
static func_openDexFileBytes openDexFileBytes; // Dalvik_dalvik_system_DexFile_openDexFile_bytearray

using func_openDexFileNative = void (*)(const uint32_t* args, int32_t* pResult);
static func_openDexFileNative openDexFileNative;

using func_dvmRawDexFileOpen = int (*)(const char* fileName, const char* odexOutputName, void* ppRawDexFile, bool isBootstrap);
static func_dvmRawDexFileOpen dvmRawDexFileOpen;

struct MemMapping {
    void*   addr;           /* start of data */
    size_t  length;         /* length of data */
    void*   baseAddr;       /* page-aligned base address */
    size_t  baseLength;     /* length of mapping */
};

struct DvmDex {
    void*            unusedPtr[7];
    bool                isMappedReadOnly;
    MemMapping          memMap;
    jobject dex_object; // use this
    jobject dex_object_htc; // htc use this
};

struct RawDexFile {
    char*       cacheFileName;
    DvmDex*     pDvmDex;
};

struct DexOrJar {
    char*       fileName;
    bool        isDex;
    bool        okayToFree;
    RawDexFile* pRawDexFile;
    void*    pJarFile;
    uint8_t*         pDexMemory; // malloc()ed memory, if any
};

static jclass sDexFileClazz;
static jfieldID sCookieField;
static jfieldID sFileNameField;
static jfieldID sGuardField;

static jclass sCloseGuardClazz;
static jmethodID sGuardGetMethod;

static jmethodID sOpenDexFileMethod;

static jclass sDexClazz;
static jmethodID sDexConstructor;

static bool sIsSpecHtc;

bool sIsSetHandler;

bool sSigFlag;

static sigjmp_buf sSigJmpBuf;

static struct sigaction OldSignalAction;

#define CHECK_EXCEPTION \
do { \
    if (env->ExceptionCheck() == JNI_TRUE) { \
        return JNI_FALSE; \
    } \
} while(false)

#define CHECK_EXCEPTION_AND_EXE_ABORT(MSG, CALL_FUNC) \
do { \
    if (env->ExceptionCheck() == JNI_TRUE) { \
        CALL_FUNC; \
        ALOGE(MSG); \
        return nullptr; \
    } \
} while(false)

#define CHECK_EXCEPTION_AND_ABORT(MSG) CHECK_EXCEPTION_AND_EXE_ABORT(MSG, )

class ScopedSetSigFlag {
public:
    ScopedSetSigFlag() {
        sSigFlag = true;
    }

    ~ScopedSetSigFlag() {
        sSigFlag = false;
    }
};

static void* MapFile(const char* file_path, uint32_t *out_file_size) {
    int fd = TEMP_FAILURE_RETRY(open(file_path, O_RDONLY, S_IRUSR));
    if (fd == -1) {
        ALOGE("fail to open %s", file_path);
        return nullptr;
    }

    uint32_t file_size = static_cast<uint32_t>(lseek(fd, 0, SEEK_END));

    ALOGV("mapping file size is %zu", file_size);

    void *ptr = mmap(nullptr, file_size, PROT_READ, MAP_SHARED, fd, 0);
    TEMP_FAILURE_RETRY(close(fd));

    if (ptr == MAP_FAILED) {
        ALOGE("fail to map file %s", file_path);
        return nullptr;
    }

    *out_file_size = file_size;
    return ptr;
}

static int64_t ObtainCheckSum(const char *file_path) {
    uint32_t file_size = 0;
    void *ptr = MapFile(file_path, &file_size);
    if (ptr == nullptr) {
        return 0;
    }

    int64_t result = adler32(0, static_cast<const Bytef *>(ptr), static_cast<uInt>(file_size));

    munmap(ptr, file_size);

    return result;
}

static func_openDexFileBytes findOpenDexFileFunc(JNINativeMethod *func, const char *name,
                                                 const char *signature = "([B)I") {
    size_t len_name = strlen(name);
    while (func->name != nullptr) {
        if ((strncmp(name, func->name, len_name) == 0)
            && (strncmp(signature, func->signature, len_name) == 0)) {
            return reinterpret_cast<func_openDexFileBytes>(func->fnPtr);
        }
        func++;
    }
    return nullptr;
}

static bool CheckIsSpecHtc() {
    try{
        const char htc[] = "htc";
        size_t len = strlen(htc);

        char brand[PROP_NAME_MAX * 3];
        __system_property_get("ro.product.brand", brand);
        if (strncasecmp(htc, brand, len) == 0) {
            return true;
        }
        __system_property_get("ro.product.manufacturer", brand);
        if (strncasecmp(htc, brand, len) == 0) {
            return true;
        }
        return false;
    } catch (...) {
        return false;
    }
}

static void OpenDexHandler(int) {
    if(sSigFlag) {
        siglongjmp(sSigJmpBuf, 1);
    } else {
        sigaction(SIGSEGV, &OldSignalAction, nullptr);
    }
}

static bool SetSignalHandler() {
    struct sigaction action{};

    if(sigemptyset(&action.sa_mask) != 0) {
        ALOGE("fail set empty mask of action");
        return false;
    }
    action.sa_handler = OpenDexHandler;

    if(sigaction(SIGSEGV, &action, &OldSignalAction) != 0) {
        ALOGE("fail set action, err=%s", strerror(errno));
        return false;
    }

    ALOGI("set action successfully");
    return true;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bytedance_boost_1multidex_BoostNative_obtainCheckSum(JNIEnv *env, jclass, jstring filePath) {
    const char *file_path = env->GetStringUTFChars(filePath, nullptr);

    jlong result = ObtainCheckSum(file_path);

    env->ReleaseStringUTFChars(filePath, file_path);

    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_bytedance_boost_1multidex_BoostNative_loadDirectDex(JNIEnv *env, jclass,
                                                             jstring jFilePath,
                                                             jbyteArray jFileContents) {
    if (sigsetjmp(sSigJmpBuf, 1) != 0) {
        ALOGE("recover and skip crash");
        return nullptr;
    }

    ScopedSetSigFlag scoped;

    // if jFilePath is null, the byte array is from a dex in zip.
    // do not support when both jFilePath and jFileContents are empty.
    int32_t cookie;
    if (sOpenDexFileMethod != nullptr) {
        if (jFileContents == nullptr) {
            uint32_t file_size = 0;
            const char *file_path = env->GetStringUTFChars(jFilePath, nullptr);
            void *ptr = MapFile(file_path, &file_size);
            env->ReleaseStringUTFChars(jFilePath, file_path);
            if (ptr == nullptr) {
                ALOGE("fail to map file");
                return nullptr;
            }

            jFileContents = env->NewByteArray(file_size);
            CHECK_EXCEPTION_AND_EXE_ABORT("fail to new bytes", munmap(ptr, file_size));

            env->SetByteArrayRegion(jFileContents, 0, file_size, static_cast<const jbyte *>(ptr));

            munmap(ptr, file_size);
            CHECK_EXCEPTION_AND_ABORT("fail to set bytes");
        }
        cookie = env->CallStaticIntMethod(sDexFileClazz, sOpenDexFileMethod, jFileContents);
        CHECK_EXCEPTION_AND_ABORT("fail to call open dex file bytes method");
    } else {
        uint32_t args[1];
        ArrayObject *array_object_ptr;
        uint32_t length;
        if (jFileContents == nullptr) {
            uint32_t file_size = 0;
            const char *file_path = env->GetStringUTFChars(jFilePath, nullptr);
            void *ptr = MapFile(file_path, &file_size);
            if (ptr == nullptr) {
                ALOGE("fail to map dex file");
                return nullptr;
            }
            env->ReleaseStringUTFChars(jFilePath, file_path);

            length = sizeof(ArrayObject) - sizeof(ArrayObject::contents) + file_size;

            array_object_ptr = static_cast<ArrayObject *>(malloc(sizeof(ArrayObject) - sizeof(ArrayObject::contents) + length));
            if (array_object_ptr == nullptr) {
                ALOGE("fail to alloc array object");
                munmap(ptr, file_size);
                return nullptr;
            }
            array_object_ptr->length = file_size;
            memcpy(array_object_ptr->contents, ptr, length);

            munmap(ptr, file_size);
        } else {
            uint32_t jarray_length = static_cast<uint32_t>(env->GetArrayLength(jFileContents));
            uint8_t * jarray_ptr = static_cast<uint8_t *>(env->GetPrimitiveArrayCritical(jFileContents, nullptr));
            length = sizeof(ArrayObject) - sizeof(ArrayObject::contents) + jarray_length;

            array_object_ptr = static_cast<ArrayObject *>(malloc(sizeof(ArrayObject) - sizeof(ArrayObject::contents) + length));
            if (array_object_ptr == nullptr) {
                ALOGE("fail to alloc array object for jFileContents");
                return nullptr;
            }
            array_object_ptr->length = jarray_length;
            memcpy(array_object_ptr->contents, jarray_ptr, length);

            env->ReleasePrimitiveArrayCritical(jFileContents, jarray_ptr, 0);
        }

        args[0] = reinterpret_cast<uint32_t>(array_object_ptr);
        openDexFileBytes(args, &cookie);

        CHECK_EXCEPTION_AND_ABORT("fail to open dex file bytes");

        if (sDexClazz != nullptr && sDexConstructor != nullptr) {
            DexOrJar* dexOrJar = reinterpret_cast<DexOrJar *>(cookie);
            if (jFileContents == nullptr) {
                jFileContents = env->NewByteArray(length);
                CHECK_EXCEPTION_AND_ABORT("fail to new array of file bytes");
                env->SetByteArrayRegion(jFileContents, 0, length,
                                        reinterpret_cast<const jbyte *>(array_object_ptr->contents));
                CHECK_EXCEPTION_AND_ABORT("fail to set array of file bytes");
            }

            jobject dex_object = env->NewGlobalRef(
                    env->NewObject(sDexClazz, sDexConstructor, jFileContents));
            if (!sIsSpecHtc) {
                dexOrJar->pRawDexFile->pDvmDex->dex_object = dex_object;
            } else {
                dexOrJar->pRawDexFile->pDvmDex->dex_object = dex_object;
                dexOrJar->pRawDexFile->pDvmDex->dex_object_htc = dex_object;
            }
        }

        free(array_object_ptr);
    }

    jobject dex_file = env->AllocObject(sDexFileClazz);
    env->SetIntField(dex_file, sCookieField, cookie); // set mCookie
    env->SetObjectField(dex_file, sFileNameField, jFilePath); // set mFileName
    env->SetObjectField(dex_file, sGuardField, env->CallStaticObjectMethod(sCloseGuardClazz, sGuardGetMethod)); // set guard

    return dex_file;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_bytedance_boost_1multidex_BoostNative_recoverAction(JNIEnv *, jclass) {
    if (sIsSetHandler) {
        sigaction(SIGSEGV, &OldSignalAction, nullptr);
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_bytedance_boost_1multidex_BoostNative_makeOptDexFile(JNIEnv *env, jclass,
                                                              jstring jFilePath,
                                                              jstring jOptFilePath) {
    if (dvmRawDexFileOpen == nullptr) {
        return JNI_FALSE;
    }

    if (sigsetjmp(sSigJmpBuf, 1) != 0) {
        ALOGE("recover and skip crash");
        return JNI_FALSE;
    }

    ScopedSetSigFlag scoped;

    const char *file_path = env->GetStringUTFChars(jFilePath, nullptr);
    const char *opt_file_path = env->GetStringUTFChars(jOptFilePath, nullptr);

    void *arg;
    int result = dvmRawDexFileOpen(file_path, opt_file_path, &arg, false);

    env->ReleaseStringUTFChars(jFilePath, file_path);
    env->ReleaseStringUTFChars(jOptFilePath, opt_file_path);

    return (result != -1) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bytedance_boost_1multidex_BoostNative_initialize(JNIEnv *env, jclass, jint sdkVersion, jclass runtimeExceptionClass) {
    jclass clazz = env->FindClass("dalvik/system/DexFile"); CHECK_EXCEPTION;
    sDexFileClazz = static_cast<jclass>(env->NewGlobalRef(clazz)); CHECK_EXCEPTION;
    sCookieField = env->GetFieldID(sDexFileClazz, "mCookie", "I"); CHECK_EXCEPTION;
    sFileNameField = env->GetFieldID(sDexFileClazz, "mFileName", "Ljava/lang/String;"); CHECK_EXCEPTION;
    sGuardField = env->GetFieldID(sDexFileClazz, "guard", "Ldalvik/system/CloseGuard;"); CHECK_EXCEPTION;

    clazz = env->FindClass("dalvik/system/CloseGuard"); CHECK_EXCEPTION;
    sCloseGuardClazz = static_cast<jclass>(env->NewGlobalRef(clazz)); CHECK_EXCEPTION;

    sGuardGetMethod = env->GetStaticMethodID(sCloseGuardClazz, "get", "()Ldalvik/system/CloseGuard;"); CHECK_EXCEPTION;

    const char* dvm = "libdvm.so";
    void* handler = dlopen(dvm, RTLD_NOW);
    if (handler == nullptr) {
        env->ThrowNew(runtimeExceptionClass, "Fail to find dvm");
        return JNI_FALSE;
    }

    dvmRawDexFileOpen = (func_dvmRawDexFileOpen) dlsym(handler, "_Z17dvmRawDexFileOpenPKcS0_PP10RawDexFileb");
    if (dvmRawDexFileOpen == nullptr) {
        ALOGE("fail to get dvm func");
    }

    if (sdkVersion < 19) {
        sOpenDexFileMethod = env->GetStaticMethodID(sDexFileClazz, "openDexFile", "([B)I");
        env->ExceptionClear();
    } else {
        // SDK = 19
        clazz = env->FindClass("com/android/dex/Dex"); CHECK_EXCEPTION;
        sDexClazz = static_cast<jclass>(env->NewGlobalRef(clazz)); CHECK_EXCEPTION;
        sDexConstructor = env->GetMethodID(sDexClazz, "<init>", "([B)V"); CHECK_EXCEPTION;
        sIsSpecHtc = CheckIsSpecHtc();
    }

    if (sOpenDexFileMethod == nullptr) {
        auto* natives_DexFile = (JNINativeMethod*) dlsym(handler, "dvm_dalvik_system_DexFile");
        if (natives_DexFile == nullptr) {
            env->ThrowNew(runtimeExceptionClass, "Fail to find DexFile symbols");
            return JNI_FALSE;
        }

        openDexFileBytes = findOpenDexFileFunc(natives_DexFile, "openDexFile", "([B)I");
        if (openDexFileBytes == nullptr) {
            return JNI_FALSE;
        }
    }

    sIsSetHandler = SetSignalHandler();
    if (!sIsSetHandler) {
        ALOGE("fail to set signal handler");
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT JNICALL jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env;

    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass clazz = env->FindClass("com/bytedance/boost_multidex/BoostNative");

    // speed up first invocation of native methods
    static JNINativeMethod native_methods[] = {
            {"obtainCheckSum",
                    "(Ljava/lang/String;)J",
                    (void *) Java_com_bytedance_boost_1multidex_BoostNative_obtainCheckSum},
    };

    if (env->RegisterNatives(clazz, native_methods,
                             sizeof(native_methods) / sizeof(native_methods[0])) != JNI_OK) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
