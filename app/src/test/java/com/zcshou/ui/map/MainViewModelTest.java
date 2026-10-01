package com.zcshou.ui.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Observer;

import com.baidu.mapapi.model.LatLng;
import com.zcshou.ui.map.MapState;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MainViewModel 纯 JVM 单测（core-testing InstantTaskExecutorRule：LiveData setValue/postValue
 * 同步执行，观察者同步送达）。
 *
 * <p>契约锚定（Phase 2 Task 9）：
 * 1. selectedPoint 桥接 MapState（含 clear）——VM 写入必须同步落 MapState（事实源不变），
 *    LiveData 镜像同步取值；初始镜像与 MapState 初始值一致；
 * 2. mockRunning 为 Activity 通知的镜像（MockLocationController 接口无回调）；
 * 3. currentAddress / searchResults 为回调线程 postValue 镜像（InstantTaskExecutorRule 下同步送达）。
 */
public class MainViewModelTest {

    @Rule
    public final InstantTaskExecutorRule mInstantTaskExecutorRule = new InstantTaskExecutorRule();

    private MapState mMapState;
    private MainViewModel mViewModel;

    @Before
    public void setUp() {
        mMapState = new MapState();
        mViewModel = new MainViewModel(mMapState);
    }

    /*---------------------------- selectedPoint 桥接 ----------------------------*/

    @Test
    public void initialSelectedPoint_mirrorsMapState() {
        // 构造即镜像：与 MapState 初始默认点（原静态字段初始值）坐标一致
        LatLng initial = mViewModel.getSelectedPoint().getValue();
        assertEquals(mMapState.getSelectedPoint().latitude, initial.latitude, 0.0);
        assertEquals(mMapState.getSelectedPoint().longitude, initial.longitude, 0.0);
    }

    @Test
    public void selectPoint_updatesValueAndBridgesMapState() {
        LatLng point = new LatLng(36.5, 117.0);

        mViewModel.selectPoint(point);

        assertSame(point, mViewModel.getSelectedPoint().getValue());
        // 事实源不变：同一写入同步落 MapState
        assertSame(point, mMapState.getSelectedPoint());
    }

    @Test
    public void clearSelectedPoint_nullsValueAndClearsMapState() {
        LatLng point = new LatLng(36.5, 117.0);
        mViewModel.selectPoint(point);

        mViewModel.clearSelectedPoint();

        // 含 clear：镜像与事实源同为 null（原 clearSelectedPoint 语义）
        assertNull(mViewModel.getSelectedPoint().getValue());
        assertNull(mMapState.getSelectedPoint());
    }

    @Test
    public void selectPoint_notifiesObserver() {
        List<LatLng> observed = new ArrayList<>();
        mViewModel.getSelectedPoint().observeForever(observed::add);

        LatLng point = new LatLng(31.2, 121.4);
        mViewModel.selectPoint(point);

        // 观察者语义：注册即收当前值，selectPoint 后同步送达新值
        assertEquals(2, observed.size());
        assertSame(point, observed.get(1));
    }

    @Test
    public void clearSelectedPoint_notifiesObserverWithNull() {
        List<LatLng> observed = new ArrayList<>();
        mViewModel.getSelectedPoint().observeForever(observed::add);
        mViewModel.selectPoint(new LatLng(36.5, 117.0));

        mViewModel.clearSelectedPoint();

        assertEquals(3, observed.size());
        assertNull(observed.get(2));
    }

    /*---------------------------- mockRunning 镜像 ----------------------------*/

    @Test
    public void setMockRunning_updatesValueAndNotifiesObserver() {
        List<Boolean> observed = new ArrayList<>();
        Observer<Boolean> observer = observed::add;
        mViewModel.getMockRunning().observeForever(observer);

        mViewModel.setMockRunning(true);
        mViewModel.setMockRunning(false);

        assertTrue(observed.get(0));
        assertEquals(Boolean.FALSE, observed.get(1));
        assertEquals(Boolean.FALSE, mViewModel.getMockRunning().getValue());
    }

    /*---------------------------- 回调线程 postValue 镜像 ----------------------------*/

    @Test
    public void postCurrentAddress_deliversValue() {
        // GeocoderHelper 回调运行在非主线程（OkHttp / Baidu SDK），postValue 送达；
        // InstantTaskExecutorRule 使其在测试内同步完成
        mViewModel.postCurrentAddress("山东省济南市历下区");

        assertEquals("山东省济南市历下区", mViewModel.getCurrentAddress().getValue());
    }

    @Test
    public void postSearchResults_deliversValue() {
        List<Map<String, Object>> data = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("POI_NAME", "泉城广场");
        data.add(row);

        mViewModel.postSearchResults(data);

        assertSame(data, mViewModel.getSearchResults().getValue());
    }

    /*---------------------------- Factory 构造注入 ----------------------------*/

    @Test
    public void factory_createsViewModelInjectedWithMapState() {
        MainViewModel viewModel = new MainViewModel.Factory(mMapState).create(MainViewModel.class);
        LatLng point = new LatLng(36.5, 117.0);

        viewModel.selectPoint(point);

        // 注入的正是调用方 MapState 单例：桥接写入落在同一实例上
        assertSame(point, mMapState.getSelectedPoint());
        assertSame(point, viewModel.getSelectedPoint().getValue());
    }
}
