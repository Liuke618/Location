package com.zcshou.gogogo;

import android.content.Context;

import com.zcshou.data.HistoryRepository;
import com.zcshou.data.SQLiteHistoryRepository;
import com.zcshou.ui.map.MapState;

/**
 * 应用级依赖容器：惰性创建跨页面共享的单例依赖。
 */
public class AppContainer {
    private final Context mAppContext;
    private HistoryRepository mHistoryRepository;
    private MapState mMapState;

    public AppContainer(Context appContext) {
        mAppContext = appContext.getApplicationContext();
    }

    public synchronized HistoryRepository getHistoryRepository() {
        if (mHistoryRepository == null) {
            mHistoryRepository = new SQLiteHistoryRepository(mAppContext);
        }
        return mHistoryRepository;
    }

    public synchronized MapState getMapState() {
        if (mMapState == null) {
            mMapState = new MapState();
        }
        return mMapState;
    }
}
