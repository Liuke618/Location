package com.yybb.location.ui.settings;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.app.ActionBar;

import com.yybb.location.BaseActivity;
import com.yybb.location.R;

/**
 * 设置页容器：承载 FragmentSettings，提供带返回入口的 ActionBar。
 */
public class SettingsActivity extends BaseActivity {

    /** 可选:锚定并滚动到指定偏好项的 key(主界面 key 缺失引导跳转时定位到"高德 API Key") */
    public static final String EXTRA_ANCHOR_KEY = "anchor_key";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /* 状态栏颜色由主题统一处理:透明状态栏 + 根布局 fitsSystemWindows,状态栏区域露出窗口背景(surface) */

        setContentView(R.layout.activity_settings);

        if (savedInstanceState == null) {
            FragmentSettings fragment = new FragmentSettings();
            String anchorKey = getIntent().getStringExtra(EXTRA_ANCHOR_KEY);
            if (anchorKey != null) {
                Bundle args = new Bundle();
                args.putString(FragmentSettings.ARG_SCROLL_TO_KEY, anchorKey);
                fragment.setArguments(args);
            }
            getSupportFragmentManager()
            .beginTransaction()
            .replace(R.id.settings, fragment)
            .commit();
        }

        /* 获取默认的顶部的标题栏（安卓称为 ActionBar）*/
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == android.R.id.home) {
            this.finish(); // back button
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}