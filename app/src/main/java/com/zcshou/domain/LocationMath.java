package com.zcshou.domain;

/**
 * 摇杆移动向量与经纬度增量的纯数学换算（无 Android 依赖，可 JVM 单测）。
 * 量纲沿用 JoyStick 原实现（行为冻结）：speed 单位 m/s，periodSec 单位 s，
 * 返回向量单位 km；经纬度增量单位为度。
 */
public final class LocationMath {

    private LocationMath() {
    }

    /**
     * 依据速度、周期、比例与角度计算本次移动向量。
     *
     * @param speed     速度（m/s）
     * @param periodSec 周期（s，如 DivGo/1000）
     * @param ratio     摇杆偏移比例（0~1，可为负，不特判）
     * @param angleDeg  角度（度，以 X 轴正向为 0 度）
     * @return double[]{eastKm, northKm}，单位 km
     */
    public static double[] moveVectorKm(double speed, double periodSec, double ratio, double angleDeg) {
        double rad = angleDeg * 2 * Math.PI / 360;
        // 注意安卓中的三角函数使用的是弧度
        double eastKm = speed * periodSec * ratio * Math.cos(rad) / 1000;
        double northKm = speed * periodSec * ratio * Math.sin(rad) / 1000;
        return new double[]{eastKm, northKm};
    }

    /**
     * 东西向距离（km）换算为经度增量（度）。
     * Longitude: 1 deg = 111.320*cos(latitude) km
     */
    public static double lngDelta(double eastKm, double latDeg) {
        return eastKm / (111.320 * Math.cos(Math.abs(latDeg) * Math.PI / 180));
    }

    /**
     * 南北向距离（km）换算为纬度增量（度）。
     * Latitude: 1 deg = 110.574 km
     */
    public static double latDelta(double northKm) {
        return northKm / 110.574;
    }
}
