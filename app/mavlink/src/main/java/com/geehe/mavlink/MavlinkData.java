package com.geehe.mavlink;


import java.util.LinkedHashMap;
import java.util.Map;

@SuppressWarnings("WeakerAccess")
public class MavlinkData {
    public final float telemetryAltitude;
    public final float telemetryPitch;
    public final float telemetryRoll;
    public final float telemetryYaw;
    public final float telemetryBattery;
    public final float telemetryCurrent;
    public final float telemetryCurrentConsumed;
    public final double telemetryLat;
    public final double telemetryLon;
    public final double telemetryLatBase;
    public final double telemetryLonBase;
    public final double telemetryHdg;
    public final double telemetryDistance;
    public final float telemetrySats;
    public final float telemetryGspeed;
    public final float telemetryVspeed;
    public final float telemetryThrottle;
    public final float telemetryArm;

    public MavlinkData() {
        this.telemetryAltitude = 0;
        this.telemetryPitch = 0;
        this.telemetryRoll = 0;
        this.telemetryYaw = 0;
        this.telemetryBattery = 0;
        this.telemetryCurrent = 0;
        this.telemetryCurrentConsumed = 0;
        this.telemetryLat = 0;
        this.telemetryLon = 0;
        this.telemetryLatBase = 0;
        this.telemetryLonBase = 0;
        this.telemetryHdg = 0;
        this.telemetryDistance = 0;
        this.telemetrySats = 0;
        this.telemetryGspeed = 0;
        this.telemetryVspeed = 0;
        this.telemetryThrottle = 0;
        this.telemetryArm = 0;
    }

    public MavlinkData(float telemetryAltitude, float telemetryPitch, float telemetryRoll, float telemetryYaw,
                       float telemetryBattery, float telemetryCurrent, float telemetryCurrentConsumed,
                       double telemetryLat, double telemetryLon, double telemetryLatBase, double telemetryLonBase,
                       double telemetryHdg, double telemetryDistance, float telemetrySats, float telemetryGspeed,
                       float telemetryVspeed, float telemetryThrottle, float telemetryArm) {
        this.telemetryAltitude = telemetryAltitude;
        this.telemetryPitch = telemetryPitch;
        this.telemetryRoll = telemetryRoll;
        this.telemetryYaw = telemetryYaw;
        this.telemetryBattery = telemetryBattery;
        this.telemetryCurrent = telemetryCurrent;
        this.telemetryCurrentConsumed = telemetryCurrentConsumed;
        this.telemetryLat = telemetryLat;
        this.telemetryLon = telemetryLon;
        this.telemetryLatBase = telemetryLatBase;
        this.telemetryLonBase = telemetryLonBase;
        this.telemetryHdg = telemetryHdg;
        this.telemetryDistance = telemetryDistance;
        this.telemetrySats = telemetrySats;
        this.telemetryGspeed = telemetryGspeed;
        this.telemetryVspeed = telemetryVspeed;
        this.telemetryThrottle = telemetryThrottle;
        this.telemetryArm = telemetryArm;
    }
}