#include <jni.h>
#include <string>

#include <sys/prctl.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/uio.h>

#include <sys/prctl.h>
#include <sys/sem.h>
#include <thread>
#include <assert.h>
#include <android/log.h>

#include "mavlink/common/mavlink.h"
#include "mavlink.h"

#define earthRadiusKm 6371.0
#define BILLION 1000000000L

# define M_PI   3.14159265358979323846  /* pi */

double deg2rad(double degrees) {
    return degrees * M_PI / 180.0;
}

struct mavlink_data {
    // Mavlink
    float telemetry_altitude;
    float telemetry_pitch;
    float telemetry_roll;
    float telemetry_yaw;
    float telemetry_battery;
    float telemetry_current;
    float telemetry_current_consumed;
    double telemetry_lat;
    double telemetry_lon;
    double telemetry_lat_base;
    double telemetry_lon_base;
    double telemetry_hdg;
    double telemetry_distance;
    double s1_double;
    double s2_double;
    double s3_double;
    double s4_double;
    float telemetry_sats;
    float telemetry_gspeed;
    float telemetry_vspeed;
    float telemetry_rssi;
    float telemetry_throttle;
    float telemetry_resolution;
    float telemetry_arm;
    float armed;
    char c1[30];
    char c2[30];
    char s1[30];
    char s2[30];
    char s3[30];
    char s4[30];
    char* ptr;
    int8_t wfb_rssi;
    uint16_t wfb_errors;
    uint16_t wfb_fec_fixed;
    int8_t wfb_flags;
} latestMavlinkData;

double distanceEarth(double lat1d, double lon1d, double lat2d, double lon2d) {
    double lat1r, lon1r, lat2r, lon2r, u, v;
    lat1r = deg2rad(lat1d);
    lon1r = deg2rad(lon1d);
    lat2r = deg2rad(lat2d);
    lon2r = deg2rad(lon2d);
    u = sin((lat2r - lat1r) / 2);
    v = sin((lon2r - lon1r) / 2);

    return 2.0 * earthRadiusKm * asin(sqrt(u * u + cos(lat1r) * cos(lat2r) * v * v));
}

size_t numOfChars(const char s[]) {
    size_t n = 0;
    while (s[n] != '\0') {
        ++n;
    }

    return n;
}

char* insertString(char s1[], const char s2[], size_t pos) {
    size_t n1 = numOfChars(s1);
    size_t n2 = numOfChars(s2);
    if (n1 < pos) {
        pos = n1;
    }

    for (size_t i = 0; i < n1 - pos; i++) {
        s1[n1 + n2 - i - 1] = s1[n1 - i - 1];
    }

    for (size_t i = 0; i < n2; i++) {
        s1[pos + i] = s2[i];
    }

    s1[n1 + n2] = '\0';

    return s1;
}

int mavlink_thread_signal = 0;
std::atomic<bool> latestMavlinkDataChange=false;

void* listen(int mavlink_port) {
    __android_log_print(ANDROID_LOG_DEBUG, "mavlink.cpp", "Starting mavlink thread...");
    // Create socket
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_DEBUG, "mavlink.cpp", "ERROR: Unable to create MavLink socket:  %s" , strerror(errno));
        return 0;
    }

    // Bind port
    struct sockaddr_in addr = {};
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    inet_pton(AF_INET, "0.0.0.0", &(addr.sin_addr));
    addr.sin_port = htons(mavlink_port);

    if (bind(fd, (struct sockaddr*)(&addr), sizeof(addr)) != 0) {
        __android_log_print(ANDROID_LOG_DEBUG, "mavlink.cpp", "ERROR: Unable to bind MavLink port: %s" , strerror(errno));
        return 0;
    }

    // Set Rx timeout
    struct timeval tv;
    tv.tv_sec = 0;
    tv.tv_usec = 100000;
    if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv)) < 0) {
        __android_log_print(ANDROID_LOG_DEBUG, "mavlink.cpp", "ERROR: Unable to bind MavLink rx timeout:  %s" , strerror(errno));
        return 0;
    }

    char buffer[2048];
    char str[1024];
    while (!mavlink_thread_signal) {
        memset(buffer, 0x00, sizeof(buffer));
        int ret = recv(fd, buffer, sizeof(buffer), 0);
        if (ret < 0) {
            continue;
        } else if (ret == 0) {
            // peer has done an orderly shutdown
            return 0;
        }

        // Parse
        // Credit to openIPC:https://github.com/OpenIPC/silicon_research/blob/master/vdec/main.c#L1020
        mavlink_message_t message;
        mavlink_status_t status;
        for (int i = 0; i < ret; ++i) {
            if (mavlink_parse_char(MAVLINK_COMM_0, buffer[i], &message, &status) == 1) {
                switch (message.msgid) {
                    case MAVLINK_MSG_ID_HEARTBEAT:
                        // handle_heartbeat(&message);
                        break;

                    case MAVLINK_MSG_ID_SYS_STATUS:
                    {
                        mavlink_sys_status_t bat;
                        mavlink_msg_sys_status_decode(&message, &bat);
                        latestMavlinkData.telemetry_battery = bat.voltage_battery;
                        latestMavlinkData.telemetry_current = bat.current_battery;
                        latestMavlinkDataChange=true;
                    }
                        break;

                    case MAVLINK_MSG_ID_BATTERY_STATUS:
                    {
                        mavlink_battery_status_t batt;
                        mavlink_msg_battery_status_decode(&message, &batt);
                        latestMavlinkData.telemetry_current_consumed = batt.current_consumed;
                        latestMavlinkDataChange=true;
                    }
                        break;

                    case MAVLINK_MSG_ID_RC_CHANNELS_RAW:
                    {
                        mavlink_rc_channels_raw_t rc_channels_raw;
                        mavlink_msg_rc_channels_raw_decode( &message, &rc_channels_raw);
                        latestMavlinkData.telemetry_rssi = rc_channels_raw.rssi;
                        latestMavlinkData.telemetry_throttle = (rc_channels_raw.chan4_raw - 1000) / 10;

                        if (latestMavlinkData.telemetry_throttle < 0) {
                            latestMavlinkData.telemetry_throttle = 0;
                        }
                        latestMavlinkData.telemetry_arm = rc_channels_raw.chan5_raw;
                        latestMavlinkData.telemetry_resolution = rc_channels_raw.chan8_raw;
                        latestMavlinkDataChange=true;
                    }
                        break;

                    case MAVLINK_MSG_ID_GPS_RAW_INT:
                    {
                        mavlink_gps_raw_int_t gps;
                        mavlink_msg_gps_raw_int_decode(&message, &gps);
                        latestMavlinkData.telemetry_sats = gps.satellites_visible;
                        latestMavlinkData.telemetry_lat = gps.lat;
                        latestMavlinkData.telemetry_lon = gps.lon;
                        if (latestMavlinkData.telemetry_arm > 1700) {
                            if (latestMavlinkData.armed < 1) {
                                latestMavlinkData.armed = 1;
                                latestMavlinkData.telemetry_lat_base = latestMavlinkData.telemetry_lat;
                                latestMavlinkData.telemetry_lon_base = latestMavlinkData.telemetry_lon;
                            }

                            sprintf(latestMavlinkData.s1, "%.00f", latestMavlinkData.telemetry_lat);
                            if (latestMavlinkData.telemetry_lat < 10000000) {
                                insertString(latestMavlinkData.s1, "0.", 0);
                            }
                            if (latestMavlinkData.telemetry_lat > 9999999) {
                                if (numOfChars(latestMavlinkData.s1) == 8) {
                                    insertString(latestMavlinkData.s1, ".", 1);
                                } else {
                                    insertString(latestMavlinkData.s1, ".", 2);
                                }
                            }

                            sprintf(latestMavlinkData.s2, "%.00f", latestMavlinkData.telemetry_lon);
                            if (latestMavlinkData.telemetry_lon < 10000000) {
                                insertString(latestMavlinkData.s2, "0.", 0);
                            }
                            if (latestMavlinkData.telemetry_lon > 9999999) {
                                if (numOfChars(latestMavlinkData.s2) == 8) {
                                    insertString(latestMavlinkData.s2, ".", 1);
                                } else {
                                    insertString(latestMavlinkData.s2, ".", 2);
                                }
                            }

                            sprintf(latestMavlinkData.s3, "%.00f", latestMavlinkData.telemetry_lat_base);
                            if (latestMavlinkData.telemetry_lat_base < 10000000) {
                                insertString(latestMavlinkData.s3, "0.", 0);
                            }
                            if (latestMavlinkData.telemetry_lat_base > 9999999) {
                                if (numOfChars(latestMavlinkData.s3) == 8) {
                                    insertString(latestMavlinkData.s3, ".", 1);
                                } else {
                                    insertString(latestMavlinkData.s3, ".", 2);
                                }
                            }

                            sprintf(latestMavlinkData.s4, "%.00f", latestMavlinkData.telemetry_lon_base);
                            if (latestMavlinkData.telemetry_lon_base < 10000000) {
                                insertString(latestMavlinkData.s4, "0.", 0);
                            }

                            if (latestMavlinkData.telemetry_lon_base > 9999999) {
                                if (numOfChars(latestMavlinkData.s4) == 8) {
                                    insertString(latestMavlinkData.s4, ".", 1);
                                } else {
                                    insertString(latestMavlinkData.s4, ".", 2);
                                }
                            }

                            latestMavlinkData.s1_double = strtod(latestMavlinkData.s1, &latestMavlinkData.ptr);
                            latestMavlinkData.s2_double = strtod(latestMavlinkData.s2, &latestMavlinkData.ptr);
                            latestMavlinkData.s3_double = strtod(latestMavlinkData.s3, &latestMavlinkData.ptr);
                            latestMavlinkData.s4_double = strtod(latestMavlinkData.s4, &latestMavlinkData.ptr);
                        }
                        latestMavlinkData.telemetry_distance = distanceEarth(latestMavlinkData.s1_double, latestMavlinkData.s2_double, latestMavlinkData.s3_double, latestMavlinkData.s4_double);
                        latestMavlinkDataChange=true;
                    }
                        break;

                    case MAVLINK_MSG_ID_VFR_HUD:
                    {
                        mavlink_vfr_hud_t vfr;
                        mavlink_msg_vfr_hud_decode(&message, &vfr);
                        latestMavlinkData.telemetry_gspeed = vfr.groundspeed * 3.6;
                        latestMavlinkData.telemetry_vspeed = vfr.climb;
                        latestMavlinkData.telemetry_altitude = vfr.alt;
                        latestMavlinkDataChange=true;
                    }
                        break;

                    case MAVLINK_MSG_ID_GLOBAL_POSITION_INT:
                    {
                        mavlink_global_position_int_t global_position_int;
                        mavlink_msg_global_position_int_decode( &message, &global_position_int);
                        latestMavlinkData.telemetry_hdg = global_position_int.hdg / 100;
                        latestMavlinkDataChange=true;
                    }
                        break;

                    case MAVLINK_MSG_ID_ATTITUDE:
                    {
                        mavlink_attitude_t att;
                        mavlink_msg_attitude_decode(&message, &att);
                        latestMavlinkData.telemetry_pitch = att.pitch * (180.0 / 3.141592653589793238463);
                        latestMavlinkData.telemetry_roll = att.roll * (180.0 / 3.141592653589793238463);
                        latestMavlinkData.telemetry_yaw = att.yaw * (180.0 / 3.141592653589793238463);
                        latestMavlinkDataChange=true;
                    }
                        break;

                    case MAVLINK_MSG_ID_RADIO_STATUS:
                    {
                        if ((message.sysid != 3) || (message.compid != 68)) {
                            break;
                        }
                        latestMavlinkData.wfb_rssi = (int8_t)mavlink_msg_radio_status_get_rssi(&message);
                        latestMavlinkData.wfb_errors = mavlink_msg_radio_status_get_rxerrors(&message);
                        latestMavlinkData.wfb_fec_fixed = mavlink_msg_radio_status_get_fixed(&message);
                        latestMavlinkData.wfb_flags = mavlink_msg_radio_status_get_remnoise(&message);
                        latestMavlinkDataChange=true;
                    }
                        break;

                    default:
                        // printf("> MavLink message %d from %d/%d\n",
                        //   message.msgid, message.sysid, message.compid);
                        break;
                }
            }
        }

        usleep(1);
    }

    __android_log_print(ANDROID_LOG_DEBUG, "mavlink.cpp", "Mavlink thread done.");
    return 0;
}


extern "C"
JNIEXPORT void JNICALL
Java_com_geehe_mavlink_MavlinkNative_nativeCallBack(JNIEnv *env, jclass clazz, jobject mavlinkChangeI) {
    //Update all java stuff
    if(latestMavlinkDataChange){
        jclass jClassExtendsMavlinkChangeI= env->GetObjectClass(mavlinkChangeI);
        jclass jcMavlinkData = env->FindClass("com/geehe/mavlink/MavlinkData");
        assert(jcMavlinkData!=nullptr);
        jmethodID jcMavlinkDataConstructor = env->GetMethodID(jcMavlinkData, "<init>", "(FFFFFFFDDDDDDFFFFF)V");
        assert(jcMavlinkDataConstructor!= nullptr);
        //const auto data = latestMavlinkData;
        auto mavlinkData=env->NewObject(jcMavlinkData,jcMavlinkDataConstructor,
                                        (jfloat)latestMavlinkData.telemetry_altitude,
                                        (jfloat)latestMavlinkData.telemetry_pitch,
                                        (jfloat)latestMavlinkData.telemetry_roll,
                                        (jfloat)latestMavlinkData.telemetry_yaw,
                                        (jfloat)latestMavlinkData.telemetry_battery,
                                        (jfloat)latestMavlinkData.telemetry_current,
                                        (jfloat)latestMavlinkData.telemetry_current_consumed,
                                        (jdouble)latestMavlinkData.telemetry_lat,
                                        (jdouble)latestMavlinkData.telemetry_lon,
                                        (jdouble)latestMavlinkData.telemetry_lat_base,
                                        (jdouble)latestMavlinkData.telemetry_lon_base,
                                        (jdouble)latestMavlinkData.telemetry_hdg,
                                        (jdouble)latestMavlinkData.telemetry_distance,
                                        (jfloat)latestMavlinkData.telemetry_sats,
                                        (jfloat)latestMavlinkData.telemetry_gspeed,
                                        (jfloat)latestMavlinkData.telemetry_vspeed,
                                        (jfloat)latestMavlinkData.telemetry_throttle,
                                        (jfloat)latestMavlinkData.telemetry_arm);
        assert(mavlinkData!=nullptr);
        jmethodID onNewMavlinkDataJAVA = env->GetMethodID(jClassExtendsMavlinkChangeI, "onNewMavlinkData", "(Lcom/geehe/mavlink/MavlinkData;)V");
        assert(onNewMavlinkDataJAVA!=nullptr);
        env->CallVoidMethod(mavlinkChangeI,onNewMavlinkDataJAVA,mavlinkData);
        latestMavlinkDataChange=false;
    }
}
extern "C"
JNIEXPORT void JNICALL
Java_com_geehe_mavlink_MavlinkNative_nativeStart(JNIEnv *env, jclass clazz, jobject context) {
    auto threadFunction = []() {
        listen(14550);
    };
    std::thread mavlink_thread(threadFunction);
    mavlink_thread.detach();
}
extern "C"
JNIEXPORT void JNICALL
Java_com_geehe_mavlink_MavlinkNative_nativeStop(JNIEnv *env, jclass clazz, jobject context) {
    mavlink_thread_signal++;
}