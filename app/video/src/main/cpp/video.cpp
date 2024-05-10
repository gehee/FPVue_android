#include <jni.h>
//#include "UdpReceiver.h"
#include "moonlight/Limelight-internal.h"

// Write C++ code here.
//
// Do not forget to dynamically load the C++ library into your application.
//
// For instance,
//
// In MainActivity.java:
//    static {
//       System.loadLibrary("video");
//    }
//
// Or, in MainActivity.kt:
//    companion object {
//      init {
//         System.loadLibrary("video")
//      }
//    }
constexpr const size_t WANTED_UDP_RCVBUF_SIZE=1024*1024*5;

extern "C"
JNIEXPORT jint JNICALL
Java_com_geehe_video_MoonBridge_start(JNIEnv *env, jclass clazz, jint socket) {
//    initializeVideoDepacketizer();
//    RtpvInitializeQueue();


}
extern "C"
JNIEXPORT jint JNICALL
Java_com_geehe_video_MoonBridge_stop(JNIEnv *env, jclass clazz) {
    // TODO: implement stop()
}