package com.yybb.location;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Activity 公共基类：当前仅透传 onCreate，作为各页面的公共扩展点。
 */
public class BaseActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}