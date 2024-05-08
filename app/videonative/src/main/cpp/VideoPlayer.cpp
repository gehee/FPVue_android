#include <jni.h>
#include "VideoPlayer.h"
#include "AndroidThreadPrioValues.hpp"
#include "helper/NDKThreadHelper.hpp"
#include "helper/NDKHelper.hpp"
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

VideoPlayer::VideoPlayer(JNIEnv* env, jobject context) :
        mParser{std::bind(&VideoPlayer::onNewNALU, this, std::placeholders::_1)},
        videoDecoder(env) {
    env->GetJavaVM(&javaVm);
    videoDecoder.registerOnDecoderRatioChangedCallback([this](const VideoRatio ratio) {
        const bool changed=ratio!=this->latestVideoRatio;
        this->latestVideoRatio=ratio;
        latestVideoRatioChanged=changed;
    });
    videoDecoder.registerOnDecodingInfoChangedCallback([this](const DecodingInfo info) {
        const bool changed=info!=this->latestDecodingInfo;
        this->latestDecodingInfo=info;
        latestDecodingInfoChanged=changed;
    });
}

//Not yet parsed bit stream (e.g. raw h264 or rtp data)
void VideoPlayer::onNewVideoData(const uint8_t* data, const std::size_t data_length,const VIDEO_DATA_TYPE videoDataType){

    //MLOGD << "onNewVideoData " << data_length;
    switch(videoDataType){
        case VIDEO_DATA_TYPE::RTP_H264:
            // MLOGD << "onNewVideoData RTP_H264 " << data_length;
            mParser.parse_rtp_h264_stream(data,data_length);
            break;
        case VIDEO_DATA_TYPE::RAW_H264:
            // mParser.parse_raw_h264_stream(data,data_length);
            // mParser.parseJetsonRawSlicedH264(data,data_length);
            break;
        case VIDEO_DATA_TYPE::RTP_H265:
            //MLOGD << "onNewVideoData RTP_H265 " << data_length;
            //rtpToNalu(data,data_length);
            mParser.parse_rtp_h265_stream(data,data_length);
            break;
        case VIDEO_DATA_TYPE::RAW_H265:
            MLOGD << "onNewVideoData RTP_H265 " << data_length;
            //mParser.parse_raw_h265_stream(data,data_length);
            break;
    }
}

void VideoPlayer::rtpToNalu(const uint8_t* data, const std::size_t data_length) {
    uint32_t rtp_header = 0;
    if (data[8] & 0x80 && data[9] & 0x60) {
        rtp_header = 12;
    }
    uint32_t nal_size = 0;
    uint8_t* nal_buffer = static_cast<uint8_t *>(malloc(1024 * 1024));
    uint8_t* nal = decodeRTPFrame(data, data_length, rtp_header, nal_buffer, &nal_size);
    if (!nal) {
        MLOGD << "no frame";
        return;
    }
    if (nal_size < 5) {
        MLOGD << "rtpToNalu broken frame";
        return;
    }
    uint8_t nal_type_hevc = (nal[4] >> 1) & 0x3F;
    __android_log_print(ANDROID_LOG_DEBUG, "com.geehe.fpvue", "nal_type_hevc=%d",nal_type_hevc);

}

uint8_t* VideoPlayer::decodeRTPFrame(const uint8_t* rx_buffer, uint32_t rx_size, uint32_t header_size, uint8_t* nal_buffer, uint32_t* out_nal_size){
    rx_buffer += header_size;
    rx_size -= header_size;

    // Get NAL type
    uint8_t fragment_type_avc = rx_buffer[0] & 0x1F;
    uint8_t fragment_type_hevc = (rx_buffer[0] >> 1) & 0x3F;

    uint8_t start_bit = 0;
    uint8_t end_bit = 0;
    uint8_t copy_size = 4;

    if (fragment_type_avc == 28 || fragment_type_hevc == 49) {
        if (fragment_type_avc == 28) {
            start_bit = rx_buffer[1] & 0x80;
            end_bit = rx_buffer[1] & 0x40;
            nal_buffer[4] = (rx_buffer[0] & 0xE0) | (rx_buffer[1] & 0x1F);
        } else {
            start_bit = rx_buffer[2] & 0x80;
            end_bit = rx_buffer[2] & 0x40;
            nal_buffer[4] = (rx_buffer[0] & 0x81) | (rx_buffer[2] & 0x3F) << 1;
            nal_buffer[5] = 1;
            copy_size++;
            rx_buffer++;
            rx_size--;
        }

        rx_buffer++;
        rx_size--;

        if (start_bit) {
            // Write NAL header
            nal_buffer[0] = 0;
            nal_buffer[1] = 0;
            nal_buffer[2] = 0;
            nal_buffer[3] = 1;

            // Copy data
            memcpy(nal_buffer + copy_size, rx_buffer, rx_size);
            in_nal_size = rx_size + copy_size;
        } else {
            rx_buffer++;
            rx_size--;
            memcpy(nal_buffer + in_nal_size, rx_buffer, rx_size);
            in_nal_size += rx_size;

            if (end_bit) {
                *out_nal_size = in_nal_size;
                in_nal_size = 0;
                return nal_buffer;
            }
        }

        return NULL;
    } else {
        // Write NAL header
        nal_buffer[0] = 0;
        nal_buffer[1] = 0;
        nal_buffer[2] = 0;
        nal_buffer[3] = 1;
        memcpy(nal_buffer + copy_size, rx_buffer, rx_size);
        *out_nal_size = rx_size + copy_size;
        in_nal_size = 0;

        // Return NAL
        return nal_buffer;
    }
}

void VideoPlayer::onNewNALU(const NALU& nalu){
    videoDecoder.interpretNALU(nalu);
}

void VideoPlayer::setVideoSurface(JNIEnv *env, jobject surface) {
    //reset the parser so the statistics start again from 0
    // mParser.reset();
    //set the jni object for settings
    videoDecoder.setOutputSurface(env, surface);
}


void VideoPlayer::start(JNIEnv *env,jobject androidContext, jstring codec) {
    AAssetManager *assetManager=NDKHelper::getAssetManagerFromContext2(env,androidContext);
    //mParser.setLimitFPS(-1); //Default: Real time !
    const int VS_PORT=5600;
    const char* codec_ = env->GetStringUTFChars(codec, nullptr);
    const int VS_PROTOCOL = strcmp(codec_, "h265")==0 ? RTP_H265 : RTP_H264;
    env->ReleaseStringUTFChars(codec, codec_);
    const auto videoDataType=static_cast<VIDEO_DATA_TYPE>(VS_PROTOCOL);
    mUDPReceiver=std::make_unique<UDPReceiver>(javaVm,VS_PORT, "V_UDP_R", -16, [this,videoDataType](const uint8_t* data, size_t data_length) {
        onNewVideoData(data,data_length,videoDataType);
    }, WANTED_UDP_RCVBUF_SIZE);
    mUDPReceiver->startReceiving();
}

void VideoPlayer::stop(JNIEnv *env,jobject androidContext) {
    if(mUDPReceiver){
        mUDPReceiver->stopReceiving();
        mUDPReceiver.reset();
    }
}

std::string VideoPlayer::getInfoString()const{
    std::stringstream ss;
    if(mUDPReceiver){
        ss << "Listening for video on port " << mUDPReceiver->getPort();
        ss << "\nReceived: " << mUDPReceiver->getNReceivedBytes() << "B"
           << " | parsed frames: ";
          // << mParser.nParsedNALUs << " | key frames: " << mParser.nParsedKonfigurationFrames;
    } else{
        ss << "Not receiving udp raw / rtp / rtsp";
    }
    return ss.str();
}


//----------------------------------------------------JAVA bindings---------------------------------------------------------------
#define JNI_METHOD(return_type, method_name) \
  JNIEXPORT return_type JNICALL              \
      Java_com_geehe_videonative_VideoPlayer_##method_name

inline jlong jptr(VideoPlayer *videoPlayerN) {
    return reinterpret_cast<intptr_t>(videoPlayerN);
}
inline VideoPlayer *native(jlong ptr) {
    return reinterpret_cast<VideoPlayer *>(ptr);
}


extern "C"{


extern "C"
JNIEXPORT jlong JNICALL
Java_com_geehe_videonative_VideoPlayer_nativeInitialize(JNIEnv *env, jclass clazz,
                                                        jobject context) {
    auto* p= new VideoPlayer(env, context);
    return jptr(p);
}

JNI_METHOD(void, nativeFinalize)
(JNIEnv * env, jclass jclass1,jlong videoPlayerN) {
    VideoPlayer* p=native(videoPlayerN);
    delete (p);
}

JNI_METHOD(void, nativeStart)
(JNIEnv * env, jclass jclass1,jlong videoPlayerN,jobject androidContext, jstring codec){
    native(videoPlayerN)->start(env,androidContext, codec);
}

JNI_METHOD(void, nativeStop)
(JNIEnv * env,jclass jclass1,jlong videoPlayerN,jobject androidContext){
    native(videoPlayerN)->stop(env,androidContext);
}

JNI_METHOD(void, nativeSetVideoSurface)
(JNIEnv * env, jclass jclass1,jlong videoPlayerN,jobject surface, jint codec){
    native(videoPlayerN)->setVideoSurface(env,surface);
}

JNI_METHOD(jstring , getVideoInfoString)
(JNIEnv *env,jclass jclass1,jlong testReceiverN) {
    VideoPlayer* p=native(testReceiverN);
    jstring ret = env->NewStringUTF(p->getInfoString().c_str());
    return ret;
}

JNI_METHOD(jboolean , anyVideoDataReceived)
(JNIEnv *env,jclass jclass1,jlong testReceiverN) {
    VideoPlayer* p=native(testReceiverN);
    if(p->mUDPReceiver== nullptr){
        return (jboolean) false;
    }
    bool ret = (p->mUDPReceiver->getNReceivedBytes() > 0);
    return (jboolean) ret;
}

JNI_METHOD(jboolean , receivingVideoButCannotParse)
(JNIEnv *env,jclass jclass1,jlong testReceiverN) {
    VideoPlayer* p=native(testReceiverN);
//    if(p->mUDPReceiver){
//        return (jboolean) (p->mUDPReceiver->getNReceivedBytes() > 1024 * 1024 && p->mParser.nParsedNALUs == 0);
//    }
    return (jboolean) false;
}

JNI_METHOD(jboolean , anyVideoBytesParsedSinceLastCall)
(JNIEnv *env,jclass jclass1,jlong testReceiverN) {
    VideoPlayer* p=native(testReceiverN);
    long nalusSinceLast = 0 ;// p->mParser.nParsedNALUs - p->nNALUsAtLastCall;
    p->nNALUsAtLastCall += nalusSinceLast;
    return (jboolean) (nalusSinceLast > 0);
}


JNI_METHOD(void,nativeCallBack)
(JNIEnv *env,jclass jclass1,jobject videoParamsChangedI, jlong testReceiverN){
    VideoPlayer* p=native(testReceiverN);
    //Update all java stuff
    if(p->latestDecodingInfoChanged || p->latestVideoRatioChanged){
        jclass jClassExtendsIVideoParamsChanged= env->GetObjectClass(videoParamsChangedI);
        if(p->latestVideoRatioChanged){
            jmethodID onVideoRatioChangedJAVA = env->GetMethodID(jClassExtendsIVideoParamsChanged, "onVideoRatioChanged", "(II)V");
            env->CallVoidMethod(videoParamsChangedI,onVideoRatioChangedJAVA,(jint)p->latestVideoRatio.width,(jint)p->latestVideoRatio.height);
            p->latestVideoRatioChanged=false;
        }
        if(p->latestDecodingInfoChanged){
            jclass jcDecodingInfo = env->FindClass("com/geehe/videonative/DecodingInfo");
            assert(jcDecodingInfo!=nullptr);
            jmethodID jcDecodingInfoConstructor = env->GetMethodID(jcDecodingInfo, "<init>", "(FFFFFIII)V");
            assert(jcDecodingInfoConstructor!= nullptr);
            const auto info=p->latestDecodingInfo;
            auto decodingInfo=env->NewObject(jcDecodingInfo,jcDecodingInfoConstructor,(jfloat)info.currentFPS,(jfloat)info.currentKiloBitsPerSecond,
                           (jfloat)info.avgParsingTime_ms,(jfloat)info.avgWaitForInputBTime_ms,(jfloat)info.avgDecodingTime_ms,(jint)info.nNALU,(jint)info.nNALUSFeeded,(jint)info.nDecodedFrames);
            assert(decodingInfo!=nullptr);
            jmethodID onDecodingInfoChangedJAVA = env->GetMethodID(jClassExtendsIVideoParamsChanged, "onDecodingInfoChanged", "(Lcom/geehe/videonative/DecodingInfo;)V");
            assert(onDecodingInfoChangedJAVA!=nullptr);
            env->CallVoidMethod(videoParamsChangedI,onDecodingInfoChangedJAVA,decodingInfo);
            p->latestDecodingInfoChanged=false;
        }
    }
}


}
