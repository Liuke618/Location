package com.yybb.location.ui.map;

import android.app.Activity;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;

import androidx.appcompat.app.AlertDialog;

import com.amap.api.maps.model.LatLng;

import com.yybb.location.R;
import com.yybb.location.utils.GoUtils;
import com.yybb.location.utils.MapUtils;

/**
 * 手动输入经纬度对话框（location_input 布局；原 MainActivity.initMapButton 内联体原样迁出，行为冻结）：
 * 校验经度 -180~180、纬度 -90~90，勾选 WGS84 时经 MapUtils 换算为 GCJ02；
 * 校验通过后回调最终 GCJ02 坐标并关闭对话框，选中点/标记名/视野等落状态由调用方完成。
 */
public class InputPositionDialog {

    /** 确认回调：参数为按所选坐标类型换算后的最终 GCJ02 坐标 */
    public interface Listener {
        void onPositionConfirmed(LatLng gcj02Point);
    }

    /** 弹出对话框；Activity 上下文用于弹窗主题与 Toast */
    public static void show(final Activity activity, final Listener listener) {
        AlertDialog dialog;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(activity.getResources().getString(R.string.input_position_title));
        View view = LayoutInflater.from(activity).inflate(R.layout.location_input, null);
        builder.setView(view);
        dialog = builder.show();

        EditText dialog_lng = view.findViewById(R.id.input_longitude);
        EditText dialog_lat = view.findViewById(R.id.input_latitude);
        RadioButton rbGcj = view.findViewById(R.id.pos_type_gcj);

        Button btnGo = view.findViewById(R.id.input_position_ok);
        btnGo.setOnClickListener(v2 -> {
            String dialog_lng_str = dialog_lng.getText().toString();
            String dialog_lat_str = dialog_lat.getText().toString();

            if (TextUtils.isEmpty(dialog_lng_str) || TextUtils.isEmpty(dialog_lat_str)) {
                GoUtils.DisplayToast(activity,activity.getResources().getString(R.string.app_error_input));
            } else {
                // 输入框 digits 放行 "." 与 "-"，"1.2.3"、"-" 这类串可解析失败；解析不过按非法输入处理
                double dialog_lng_double = GoUtils.parseDoubleOrDefault(dialog_lng_str, Double.NaN);
                double dialog_lat_double = GoUtils.parseDoubleOrDefault(dialog_lat_str, Double.NaN);

                if (Double.isNaN(dialog_lng_double) || Double.isNaN(dialog_lat_double)) {
                    GoUtils.DisplayToast(activity,activity.getResources().getString(R.string.app_error_input));
                } else if (dialog_lng_double > 180.0 || dialog_lng_double < -180.0) {
                    GoUtils.DisplayToast(activity,  activity.getResources().getString(R.string.app_error_longitude));
                } else {
                    if (dialog_lat_double > 90.0 || dialog_lat_double < -90.0) {
                        GoUtils.DisplayToast(activity,  activity.getResources().getString(R.string.app_error_latitude));
                    } else {
                        // 勾选 GCJ02 直接用输入值，否则按 WGS84 经 MapUtils 换算（坐标系转换只经 MapUtils）
                        LatLng gcj02Point;
                        if (rbGcj.isChecked()) {
                            gcj02Point = new LatLng(dialog_lat_double, dialog_lng_double);
                        } else {
                            double[] gcjLonLat = MapUtils.wgs2gcj(dialog_lng_double, dialog_lat_double);
                            gcj02Point = new LatLng(gcjLonLat[1], gcjLonLat[0]);
                        }
                        listener.onPositionConfirmed(gcj02Point);

                        dialog.dismiss();
                    }
                }
            }
        });

        Button btnCancel = view.findViewById(R.id.input_position_cancel);
        btnCancel.setOnClickListener(v1 -> dialog.dismiss());
    }
}
