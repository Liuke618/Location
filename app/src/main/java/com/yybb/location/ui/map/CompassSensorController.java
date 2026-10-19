package com.yybb.location.ui.map;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * 罗盘（加速度+地磁）传感器：注册/注销与方向角计算（原 MainActivity 传感器字段、
 * onSensorChanged 计算体与注册/注销方法原样迁出，行为冻结）；方向经回调交调用方注入定位源。
 */
public class CompassSensorController implements SensorEventListener {

    /** 方向回调：0~360 度，取值语义与迁出前注入定位源的一致 */
    public interface DirectionListener {
        void onDirection(float direction);
    }

    private final SensorManager mSensorManager;
    private final Sensor mSensorAccelerometer;
    private final Sensor mSensorMagnetic;
    private final DirectionListener mListener;
    private float[] mAccValues = new float[3];//加速度传感器数据
    private float[] mMagValues = new float[3];//地磁传感器数据
    private final float[] mR = new float[9];//旋转矩阵，用来保存磁场和加速度的数据
    private final float[] mDirectionValues = new float[3];//模拟方向传感器的数据（原始数据为弧度）
    private float mCurrentDirection = 0.0f;

    public CompassSensorController(Context context, DirectionListener listener) {
        mListener = listener;
        mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);// 获取传感器管理服务
        if (mSensorManager != null) {
            mSensorAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            mSensorMagnetic = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        } else {
            mSensorAccelerometer = null;
            mSensorMagnetic = null;
        }
    }

    /**
     * 罗盘（加速度+地磁）监听注册：SensorManager 不可用或设备缺少对应传感器时必须整体跳过——
     * 原实现在 onResume 无条件 registerListener，缺传感器时传入 null sensor 即崩溃。
     */
    public void register() {
        if (mSensorManager == null) {
            return;
        }
        if (mSensorAccelerometer != null) {
            mSensorManager.registerListener(this, mSensorAccelerometer, SensorManager.SENSOR_DELAY_UI);
        }
        if (mSensorMagnetic != null) {
            mSensorManager.registerListener(this, mSensorMagnetic, SensorManager.SENSOR_DELAY_UI);
        }
    }

    /**
     * 罗盘监听注销：unregisterListener 本身可重复调用，收口只为与注册共用同一处判空。
     */
    public void unregister() {
        if (mSensorManager != null) {
            mSensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if(sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER){
            mAccValues = sensorEvent.values;
        }
        else if(sensorEvent.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD){
            mMagValues = sensorEvent.values;
        }

        SensorManager.getRotationMatrix(mR, null, mAccValues, mMagValues);
        SensorManager.getOrientation(mR, mDirectionValues);
        mCurrentDirection = (float) Math.toDegrees(mDirectionValues[0]);    // 弧度转角度
        if (mCurrentDirection < 0) {    // 由 -180 ~ + 180 转为 0 ~ 360
            mCurrentDirection += 360;
        }

        mListener.onDirection(mCurrentDirection);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }
}
