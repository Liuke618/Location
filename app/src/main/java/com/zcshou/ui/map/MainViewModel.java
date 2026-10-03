package com.zcshou.ui.map;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.amap.api.maps.model.LatLng;
import com.zcshou.ui.map.MapState;

import java.util.List;
import java.util.Map;

/**
 * 主界面状态持有器（Phase 2 收尾）：仅承载状态镜像，不含业务逻辑、不持 Context、不触高德 SDK。
 * 依赖经 {@link Factory} 构造注入（MapState 来自 GoApplication.AppContainer 单例）。
 *
 * <p>四项 LiveData 契约（Phase 2 Task 9）：
 * <ul>
 * <li>{@link #getSelectedPoint()}：桥接 MapState（含 clear）。MapState 仍为唯一事实源
 *     （AppContainer 单例、跨页面共享），Activity 驱动的写入经 {@link #selectPoint(LatLng)} /
 *     {@link #clearSelectedPoint()} 同步落 MapState 并同步镜像；MapController 内部写入
 *     （resetMap / showLocation，含 HistoryActivity 静态门面路径）不经本类，镜像不覆盖该路径。</li>
 * <li>{@link #getMockRunning()}：MockLocationController 运行状态镜像。该接口无回调，
 *     由 Activity 在 start/stop 调用点通知（计划允许"由 controller 回调/Activity 通知"）。</li>
 * <li>{@link #getCurrentAddress()}：GeocoderHelper 回调结果镜像（SDK 逆地理地址与 REST
 *     逆地理地址）。回调运行在非主线程，经 postValue 异步送达。</li>
 * <li>{@link #getSearchResults()}：PoiSearchHelper 建议结果镜像。SDK 回调线程 postValue。</li>
 * </ul>
 *
 * <p>行为冻结说明：四条数据流的渲染路径保持原直接调用（helpers → MainActivity 回调 → UI），
 * Activity 不经 LiveData 观察者渲染——渲染跟随的是"具体流程"而非"状态值"（选中点各流程的
 * 后续渲染各不相同：markMap / setMapStatus / animateMapStatus / 无），且观察者会在配置重建时
 * 重放状态值、在 postValue 下重排线程时序，二者均属可观察行为变化。本类定位为状态镜像持有者，
 * 供后续阶段在专门的行为评审下迁移渲染路径（见 Task 9 报告）。
 */
public class MainViewModel extends ViewModel {

    private final MapState mMapState;

    /* 选中点镜像（初始值与 MapState 初始默认点一致，即原静态字段初始值） */
    private final MutableLiveData<LatLng> mSelectedPoint = new MutableLiveData<>();
    /* 模拟运行镜像（初始 null：Activity 首次通知前无已知状态） */
    private final MutableLiveData<Boolean> mMockRunning = new MutableLiveData<>();
    /* 当前地址镜像（GeocoderHelper 结果） */
    private final MutableLiveData<String> mCurrentAddress = new MutableLiveData<>();
    /* 搜索建议结果镜像（PoiSearchHelper 结果） */
    private final MutableLiveData<List<Map<String, Object>>> mSearchResults = new MutableLiveData<>();

    /* 生产路径经 Factory；包私有构造供 Factory 与单测使用 */
    MainViewModel(@NonNull MapState mapState) {
        mMapState = mapState;
        mSelectedPoint.setValue(mMapState.getSelectedPoint());
    }

    /**
     * 选中点桥接写入：同步落 MapState（事实源，语义与原 setSelectedPoint 一致），
     * 并同步镜像到 LiveData（主线程 setValue，与各调用点所在主线程回调一致）。
     */
    public void selectPoint(@NonNull LatLng point) {
        mMapState.setSelectedPoint(point);
        mSelectedPoint.setValue(point);
    }

    /**
     * 选中点桥接清除（原 MapState.clearSelectedPoint 语义：置 null），同步镜像。
     */
    public void clearSelectedPoint() {
        mMapState.clearSelectedPoint();
        mSelectedPoint.setValue(null);
    }

    public LiveData<LatLng> getSelectedPoint() {
        return mSelectedPoint;
    }

    /**
     * 模拟运行状态镜像：Activity 在 start/stop 调用点通知（setValue 主线程，与调用点一致）。
     */
    public void setMockRunning(boolean running) {
        mMockRunning.setValue(running);
    }

    public LiveData<Boolean> getMockRunning() {
        return mMockRunning;
    }

    /**
     * 当前地址镜像：GeocoderHelper 回调线程（高德 SDK）调用，postValue 异步送达
     * 主线程观察者；不改写回调自身的线程模型与后续落库/渲染时序。
     */
    public void postCurrentAddress(String address) {
        mCurrentAddress.postValue(address);
    }

    public LiveData<String> getCurrentAddress() {
        return mCurrentAddress;
    }

    /**
     * 建议结果镜像：PoiSearchHelper SDK 回调线程调用，postValue 异步送达；
     * 建议列表渲染保持原回调线程直接渲染路径（时序不变，见类注释行为冻结说明）。
     */
    public void postSearchResults(List<Map<String, Object>> results) {
        mSearchResults.postValue(results);
    }

    public LiveData<List<Map<String, Object>>> getSearchResults() {
        return mSearchResults;
    }

    /**
     * 构造注入 Factory：注入 AppContainer 单例 MapState（Activity 于 onCreate 传入）。
     */
    public static class Factory implements ViewModelProvider.Factory {
        private final MapState mMapState;

        public Factory(@NonNull MapState mapState) {
            mMapState = mapState;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            if (modelClass.isAssignableFrom(MainViewModel.class)) {
                return (T) new MainViewModel(mMapState);
            }
            throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
        }
    }
}
