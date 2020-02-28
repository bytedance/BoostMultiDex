#include <jni.h>
#include <string>
#include <cstdlib>

extern "C" JNIEXPORT jstring JNICALL
Java_com_bytedance_app_boost_1multidex_MainActivity_stringFromJNI(
        JNIEnv *env,
        jobject /* this */) {
    std::string hello = "Hello BoostMultiDex!";
//    abort();
    return env->NewStringUTF(hello.c_str());
}