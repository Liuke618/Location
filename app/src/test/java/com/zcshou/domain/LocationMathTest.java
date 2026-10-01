package com.zcshou.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * LocationMath 纯 JVM 单测（无 Android 依赖）。
 * 数值锚定 JoyStick.java 现有实现的量纲（行为冻结，不修正单位）：
 * disLng = speed * (DivGo/1000) * ratio * cos(angle*2π/360) / 1000
 */
public class LocationMathTest {

    private static final double DELTA = 1e-12;

    @Test
    public void moveVectorKm_angleZero_goesEast() {
        // speed=1.2 m/s, periodSec=1.0, ratio=1.0, angle=0 → east = 1.2*1.0*1.0*cos(0)/1000 = 0.0012 km
        double[] v = LocationMath.moveVectorKm(1.2, 1.0, 1.0, 0);
        assertEquals(0.0012, v[0], DELTA);
        assertEquals(0.0, v[1], DELTA);
    }

    @Test
    public void moveVectorKm_angleNinety_goesNorth() {
        // angle=90（度，按 θ*2π/360 转弧度）→ east≈0、north = 0.0012 km
        double[] v = LocationMath.moveVectorKm(1.2, 1.0, 1.0, 90);
        assertEquals(0.0, v[0], DELTA);
        assertEquals(0.0012, v[1], DELTA);
    }

    @Test
    public void moveVectorKm_ratioHalvesVector() {
        // ratio=0.5 → 向量减半
        double[] v = LocationMath.moveVectorKm(1.2, 1.0, 0.5, 0);
        assertEquals(0.0006, v[0], DELTA);
        assertEquals(0.0, v[1], DELTA);
    }

    @Test
    public void moveVectorKm_negativeAngle_passthrough() {
        // 原实现不特判负角：-90° → sin 为负，north = -0.0012
        double[] v = LocationMath.moveVectorKm(1.2, 1.0, 1.0, -90);
        assertEquals(0.0, v[0], DELTA);
        assertEquals(-0.0012, v[1], DELTA);
    }

    @Test
    public void moveVectorKm_negativeRatio_passthrough() {
        // 原实现不特判负 ratio
        double[] v = LocationMath.moveVectorKm(1.2, 1.0, -1.0, 0);
        assertEquals(-0.0012, v[0], DELTA);
        assertEquals(0.0, v[1], DELTA);
    }

    @Test
    public void lngDelta_atEquator() {
        // lat=0 → eastKm / 111.320
        assertEquals(1.0 / 111.320, LocationMath.lngDelta(1.0, 0), DELTA);
    }

    @Test
    public void lngDelta_atLat60() {
        // lat=60 → eastKm / (111.320*cos(60°)) ≈ eastKm/55.66
        double expected = 1.0 / (111.320 * Math.cos(60 * Math.PI / 180));
        assertEquals(expected, LocationMath.lngDelta(1.0, 60), DELTA);
        assertEquals(1.0 / 55.66, LocationMath.lngDelta(1.0, 60), 1e-4);
    }

    @Test
    public void lngDelta_negativeLat_usesAbsoluteLatitude() {
        // 原内联实现为 Math.abs(mCurLat)，负纬度行为冻结：与正纬度一致
        assertEquals(LocationMath.lngDelta(1.0, 60), LocationMath.lngDelta(1.0, -60), 0.0);
    }

    @Test
    public void latDelta_oneKmNorth() {
        // northKm / 110.574
        assertEquals(1.0 / 110.574, LocationMath.latDelta(1.0), DELTA);
        assertEquals(0.0012 / 110.574, LocationMath.latDelta(0.0012), DELTA);
    }
}
