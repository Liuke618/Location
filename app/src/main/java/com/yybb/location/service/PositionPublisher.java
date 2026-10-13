package com.yybb.location.service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 模拟位置的发布/读取载体：主线程（onStartCommand 的 intent extra、ServiceGoBinder.setPosition）
 * 写，供给线程（setLocationGPS / setLocationNetwork）读。
 *
 * <p>三轴装在同一个不可变快照里整体替换：三个独立的 double 字段允许"新经度配旧纬度"，
 * 那等于把用户没选过的坐标瞬移过去（PositionPublisherTest 有并发串样的回归用例）。
 */
public final class PositionPublisher {

    public static final double DEFAULT_LAT = 36.667662;
    public static final double DEFAULT_LNG = 117.027707;
    public static final double DEFAULT_ALT = 55.0D;

    private final AtomicReference<Snapshot> mCurrent =
            new AtomicReference<>(new Snapshot(DEFAULT_LNG, DEFAULT_LAT, DEFAULT_ALT));

    /** 发布一次传送的目标位置（WGS84 经纬度与海拔）。 */
    public void publish(double lng, double lat, double alt) {
        mCurrent.set(new Snapshot(lng, lat, alt));
    }

    /** 取当前应供给的位置；返回的快照三轴必然来自同一次发布。 */
    public Snapshot current() {
        return mCurrent.get();
    }

    /** 一次供给读取到的位置三轴（final 字段，构造完成即冻结，可安全跨线程发布）。 */
    public static final class Snapshot {
        public final double lng;
        public final double lat;
        public final double alt;

        Snapshot(double lng, double lat, double alt) {
            this.lng = lng;
            this.lat = lat;
            this.alt = alt;
        }
    }
}
