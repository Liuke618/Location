package com.yybb.location;

import android.content.Context;

import com.yybb.location.data.HistoryRepository;
import com.yybb.location.data.SQLiteHistoryRepository;
import com.yybb.location.ui.map.MapState;

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
