#ifndef FPV_VR_WFBNG_LINK_H
#define FPV_VR_WFBNG_LINK_H

#include <jni.h>
#include <list>
#include "wfb-ng/src/rx.hpp"
#include "devourer/src/WiFiDriver.h"

class WfbngLink{
public:
    WfbngLink(JNIEnv * env, jobject context, std::list<int> fds);
    int run(JNIEnv *env,jobject androidContext, jint wifiChannel);
    void stop(JNIEnv *env,jobject androidContext);
    Aggregator* aggregator;

private:
    const char *keyPath = "/data/user/0/com.geehe.fpvue/files/gs.key";
    std::list<int> deviceDescriptors;
    std::list<struct libusb_device_handle *> devHandles;
    std::list<std::unique_ptr<Rtl8812aDevice>> rtlDevices;
    bool should_stop;
};

#endif //FPV_VR_WFBNG_LINK_H
