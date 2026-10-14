package com.yybb.location.ui.settings;

import android.os.Bundle;
import android.text.InputType;

import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.elvishew.xlog.XLog;
import com.yybb.location.utils.AmapUtils;
import com.yybb.location.utils.GoUtils;
import com.yybb.location.R;

/**
 * 设置页（PreferenceFragmentCompat）：偏好项展示与变更监听，数值输入做格式校验（如历史记录有效期 setting_history_expiration）。
 */
public class FragmentSettings extends PreferenceFragmentCompat {

    /** 参数键:锚定并滚动到指定偏好项(SettingsActivity.EXTRA_ANCHOR_KEY 经 args 透传) */
    public static final String ARG_SCROLL_TO_KEY = "arg_scroll_to_key";

    // Set a non-empty decimal EditTextPreference
    private void setupDecimalEditTextPreference(EditTextPreference preference) {
        if (preference != null) {
            preference.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) EditTextPreference::getText);
            preference.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_CLASS_NUMBER);
                editText.setSelection(editText.length());
            });
            preference.setOnPreferenceChangeListener((pref, newValue) -> {
                if (newValue.toString().trim().isEmpty()) {
                    GoUtils.DisplayToast(this.getContext(), getResources().getString(R.string.app_error_input_null));
                    return false;
                }
                return true;
            });
        }
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.preferences_main);

        EditTextPreference pfAltitude = findPreference("setting_altitude");
        setupDecimalEditTextPreference(pfAltitude);

        EditTextPreference pfLatOffset = findPreference("setting_lat_max_offset");
        setupDecimalEditTextPreference(pfLatOffset);

        EditTextPreference pfLonOffset = findPreference("setting_lon_max_offset");
        setupDecimalEditTextPreference(pfLonOffset);

        // 模拟位置写入频率:摘要=当前档位+说明(SimpleSummaryProvider 只回显档位,不带解释文案)
        ListPreference pfMockInterval = findPreference("setting_mock_write_interval");
        if (pfMockInterval != null) {
            pfMockInterval.setSummaryProvider((Preference.SummaryProvider<ListPreference>) pref -> {
                CharSequence entry = pref.getEntry();
                return getString(R.string.setting_mock_write_interval_summary, entry == null ? "" : entry);
            });
        }

        // 高德 API Key：摘要只显示配置状态、不回显明文；允许留空(回落内置 Key)，不做非空校验
        EditTextPreference pfAmapKey = findPreference(AmapUtils.PREF_KEY_AMAP_API_KEY);
        if (pfAmapKey != null) {
            pfAmapKey.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) pref -> {
                String value = pref.getText();
                boolean custom = value != null && !value.trim().isEmpty();
                return getString(custom
                        ? R.string.setting_amap_api_key_set
                        : R.string.setting_amap_api_key_unset);
            });
        }

        // 高德 Key 申请指引:非持久入口,弹步骤(含包名与签名 SHA1 对照值)
        Preference pfAmapKeyGuide = findPreference("setting_amap_api_key_guide");
        if (pfAmapKeyGuide != null) {
            pfAmapKeyGuide.setOnPreferenceClickListener(preference -> {
                AmapKeyGuide.show(requireActivity());
                return true;
            });
        }

        // 主界面 key 缺失引导跳转时锚定到指定项
        Bundle arguments = getArguments();
        if (arguments != null) {
            String scrollToKey = arguments.getString(ARG_SCROLL_TO_KEY);
            if (scrollToKey != null) {
                scrollToPreference(scrollToKey);
            }
        }

        SwitchPreferenceCompat pLog = findPreference("setting_log_off");
        if (pLog != null) {
            pLog.setOnPreferenceChangeListener((preference, newValue) -> {
                if(((SwitchPreferenceCompat) preference).isChecked() != (Boolean) newValue) {
                    XLog.d(preference.getKey() + newValue);

                    if (Boolean.parseBoolean(newValue.toString())) {
                        XLog.d("on");
                    } else {
                        XLog.d("off");
                    }
                    return true;
                } else {
                    return false;
                }
            });
        }

        EditTextPreference pfPosHisValid = findPreference("setting_history_expiration");
        setupDecimalEditTextPreference(pfPosHisValid);

        // 设置版本号
        String verName;
        verName = GoUtils.getVersionName(FragmentSettings.this.getContext());
        Preference pfVersion = findPreference("setting_version");
        if (pfVersion != null) {
            pfVersion.setSummary(verName);
        }
    }
}