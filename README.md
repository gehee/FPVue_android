# FPVue_android

FPVue Android is an app packaging multiple pieces together to decode an H264/H265 video feed broadcast by wfb-ng over the air.

- [devourer](https://github.com/openipc/devourer): userspace rtl8812au driver initially created by [buldo](https://github.com/buldo) and converted to C by [josephnef](https://github.com/josephnef) .
- [LiveVideo10ms](https://github.com/Consti10/LiveVideo10ms): excellent video decoder from [Consti10](https://github.com/Consti10) converted into a module.
- [wfb-ng](https://github.com/svpcom/wfb-ng): library allowing the broadcast of the video feed over the air.

The wfb-ng key is embedded in the app for now. 
You can download [gs.key](https://github.com/gehee/FPVue_android/raw/main/app/src/main/assets/gs.key) from this repository and copy it on the air side.

Supported rtl8812au wifi adapter are listed [here](https://github.com/gehee/FPVue_android/blob/main/app/src/main/res/xml/usb_device_filter.xml).
Feel free to send pull requests to add new supported wifi adapters hardware IDs.

## Compatibility

- arm64-v8a android devices (including Meta Quest 2/3, non vr mode)

## Build

This project can be open in android studio and built from there.

## Installation instructions

1 - Download apk from https://github.com/gehee/FPVue_android/releases.
2 - Run `adb install FPVue_android_0.14.apk` from the command line.
3 - Download [gs.key](https://github.com/gehee/FPVue_android/raw/main/app/src/main/assets/gs.key), rename it to drone.key and copy it on the air side.