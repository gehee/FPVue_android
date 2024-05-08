//
// Created by Constantin on 1/9/2019.
//

#ifndef FPV_VR_VIDEOPLAYERN_H
#define FPV_VR_VIDEOPLAYERN_H

#include <jni.h>
#include "VideoDecoder.h"
#include "UdpReceiver.h"
#include "parser/H26XParser.h"

class VideoPlayer{
public:
    VideoPlayer(JNIEnv * env, jobject context);
    enum VIDEO_DATA_TYPE{RTP_H264,RAW_H264,RTP_H265,RAW_H265};
    void onNewVideoData(const uint8_t* data,const std::size_t data_length,const VIDEO_DATA_TYPE videoDataType);
    /*
     * Set the surface the decoder can be configured with. When @param surface==nullptr
     * It is guaranteed that the surface is not used by the decoder anymore when this call returns
     */
    void setVideoSurface(JNIEnv* env, jobject surface);
    /*
     * Start the receiver and ground recorder if enabled
     */
    void start(JNIEnv *env,jobject androidContext, jstring codec);
    /**
     * Stop the receiver and ground recorder if enabled
     */
    void stop(JNIEnv *env,jobject androidContext);
    /*
     * Returns a string with the current configuration for debugging
     */
    std::string getInfoString()const;
private:
    void onNewNALU(const NALU& nalu);
    void rtpToNalu(const uint8_t* data, const std::size_t data_length);
    uint32_t in_nal_size = 0;
    uint8_t* decodeRTPFrame(const uint8_t* rx_buffer, uint32_t rx_size, uint32_t header_size, uint8_t* nal_buffer, uint32_t* out_nal_size);
    //Assumptions: Max bitrate: 40 MBit/s, Max time to buffer: 100ms
    //5 MB should be plenty !
    static constexpr const size_t WANTED_UDP_RCVBUF_SIZE=1024*1024*5;
    // Retrieve settings from shared preferences
    enum SOURCE_TYPE_OPTIONS{UDP,FILE,ASSETS,VIA_FFMPEG_URL,EXTERNAL};
    const std::string GROUND_RECORDING_DIRECTORY;
    JavaVM* javaVm=nullptr;
    H26XParser mParser;
public:
    VideoDecoder videoDecoder;
    std::unique_ptr<UDPReceiver> mUDPReceiver;
    long nNALUsAtLastCall=0;
public:
    DecodingInfo latestDecodingInfo{};
    std::atomic<bool> latestDecodingInfoChanged=false;
    VideoRatio latestVideoRatio{};
    std::atomic<bool> latestVideoRatioChanged=false;

    bool lastFrameWasAUD=false;
};

#endif //FPV_VR_VIDEOPLAYERN_H
