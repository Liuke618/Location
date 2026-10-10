package com.zcshou.service;

/**
 * 模拟位置控制器接口。
 *
 * <p>用于解耦界面层（MainActivity）与模拟位置前台服务（ServiceGo）的 Binder 细节，
 * 界面只依赖本接口，不直接接触 ServiceConnection / ServiceGoBinder。
 */
public interface MockLocationController {
    /**
     * 启动模拟位置前台服务。
     *
     * @param lng 目标位置经度（WGS 84）
     * @param lat 目标位置纬度（WGS 84）
     * @param alt 目标位置海拔（米）
     * @return 服务是否已确实交由系统拉起；false 表示未启动（如缺少定位权限），调用方不得当作成功反馈
     */
    boolean start(double lng, double lat, double alt);

    /** 停止模拟位置前台服务并解除绑定。 */
    void stop();

    /**
     * 服务运行期间传送至新位置。
     *
     * @param lng 新位置经度（WGS 84）
     * @param lat 新位置纬度（WGS 84）
     * @param alt 新位置海拔（米）
     * @return 坐标是否已确实交付给供给循环；false 表示服务尚未连接或供给未建立
     */
    boolean setPosition(double lng, double lat, double alt);

    /** @return 模拟位置服务是否已启动 */
    boolean isRunning();
}
