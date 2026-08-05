package com.teemo.launcher;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.hardware.Camera;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.util.TypedValue;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements View.OnTouchListener {

    // 手势回调接口（供 LauncherScrollView 使用）
    interface GestureListener {
        void onSwipeBack();
    }

    // 页面状态
    private static final int PAGE_CLOCK = 0;
    private static final int PAGE_APPS = 1;
    private static final int PAGE_CONTROL = 2;

    // ---------- 设计基准 240x240，运行期按屏幕实际尺寸缩放 ----------
    private static final int DESIGN_SIZE = 240;

    // 时钟字体（设计值，像素）
    private static final int TIME_TEXT_SIZE = 48;
    private static final int DATE_TEXT_SIZE = 20;

    // 状态栏
    private static final int STATUS_BAR_HEIGHT = 20;
    private static final int STATUS_TEXT_SIZE = 12;

    // 应用网格（设计值，像素）
    private static final int APP_CELL_HEIGHT = 64;
    private static final int APP_ICON_SIZE = 40;
    private static final int APP_GRID_TEXT_SIZE = 12;

    // 控制中心
    private static final int LABEL_TEXT_SIZE = 18;
    private static final int TOGGLE_TEXT_SIZE = 16;
    private static final int SEEK_BAR_PADDING = 20;
    private static final int LABEL_PADDING_VERTICAL = 6;

    // 手势
    private static final int SWIPE_THRESHOLD = 30;   // 设计值，触发阈值（乘 scale）

    // 根布局
    private FrameLayout rootLayout;

    // 页面容器
    private FrameLayout clockPage;
    private LinearLayout clockBody;
    private LauncherScrollView appsScrollView;
    private LinearLayout appsContainer;
    private LinearLayout controlPage;
    private LauncherScrollView controlScroll;
    private LinearLayout controlContent;

    // 时钟控件
    private TextView timeText;
    private TextView dateText;

    // 状态栏
    private TextView statusBattery;
    private TextView statusWifi;
    private TextView statusBt;

    // 控制中心
    private SeekBar volumeSeek;
    private SeekBar brightSeek;
    private TextView wifiToggle;
    private TextView btToggle;
    private TextView flashToggle;
    private AudioManager audioManager;
    private WifiManager wifiManager;

    // 手电筒
    private Camera camera;
    private boolean flashlightOn = false;

    // 屏幕尺寸与缩放
    private int screenW, screenH, base;
    private float scale;

    // 手势
    private float downX, downY;
    private int currentPage = PAGE_CLOCK;
    private boolean isSliding = false;

    // 状态接收器（电池 / WiFi / 蓝牙）
    private int batteryLevel = -1;
    private boolean statusRegistered = false;
    private BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
                int sc = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                batteryLevel = (sc > 0) ? level * 100 / sc : 0;
            }
            updateStatusBar();
        }
    };

    // 时钟更新（单链：只在 onResume 调度，onPause 移除）
    private Handler clockHandler = new Handler();
    private Runnable clockRunnable = new Runnable() {
        @Override
        public void run() {
            updateClock();
            clockHandler.postDelayed(this, 60000);
        }
    };

    // 应用信息
    private static class AppInfo {
        String name;
        String packageName;
        Drawable icon;
        Intent intent;
    }
    private List<AppInfo> appList = new ArrayList<AppInfo>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全屏无标题
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // 初始化系统服务
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);

        // 计算屏幕尺寸与缩放比例（必须在 buildUI 前）
        computeMetrics();

        // 构建UI（所有尺寸经 px() 按屏幕缩放）
        buildUI();

        // 加载应用列表（后台线程）
        loadApps();

        // 时钟立即刷新（后续由 onResume 启动定时链）
        updateClock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateClock();
        clockHandler.postDelayed(clockRunnable, 1000);
        registerStatusReceiver();
        updateStatusBar();
        updateToggleUI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        clockHandler.removeCallbacks(clockRunnable);
        unregisterStatusReceiver();
        releaseFlashlight();
    }

    // ---------- 屏幕尺寸与缩放 ----------
    private void computeMetrics() {
        Point p = new Point();
        getWindowManager().getDefaultDisplay().getSize(p);
        screenW = p.x;
        screenH = p.y;
        base = Math.min(screenW, screenH);
        scale = base / (float) DESIGN_SIZE;
        if (scale <= 0) scale = 1f;
    }

    // 设计值 -> 实际像素
    private int px(int design) {
        return Math.round(design * scale);
    }

    // 文本按像素设置
    private void setTextPx(TextView tv, int design) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(design));
    }

    // ---------- 构建UI ----------
    private void buildUI() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(0xFF000000);
        rootLayout.setOnTouchListener(this);

        // 1. 时钟页面（FrameLayout：顶部状态栏 + 居中时钟）
        clockPage = new FrameLayout(this);
        clockPage.setBackgroundColor(0xFF000000);
        FrameLayout.LayoutParams clockParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        clockPage.setLayoutParams(clockParams);

        // 状态栏
        LinearLayout statusBar = new LinearLayout(this);
        statusBar.setOrientation(LinearLayout.HORIZONTAL);
        statusBar.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams sbParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, px(STATUS_BAR_HEIGHT));
        sbParams.gravity = Gravity.TOP;
        statusBar.setLayoutParams(sbParams);
        statusBar.setBackgroundColor(0xFF111111);

        statusBattery = new TextView(this);
        statusBattery.setTextColor(0xFFCCCCCC);
        setTextPx(statusBattery, STATUS_TEXT_SIZE);
        statusBattery.setGravity(Gravity.CENTER);
        statusBattery.setLayoutParams(new LinearLayout.LayoutParams(0, px(STATUS_BAR_HEIGHT), 1f));

        statusWifi = new TextView(this);
        statusWifi.setTextColor(0xFFCCCCCC);
        setTextPx(statusWifi, STATUS_TEXT_SIZE);
        statusWifi.setGravity(Gravity.CENTER);
        statusWifi.setLayoutParams(new LinearLayout.LayoutParams(0, px(STATUS_BAR_HEIGHT), 1f));

        statusBt = new TextView(this);
        statusBt.setTextColor(0xFFCCCCCC);
        setTextPx(statusBt, STATUS_TEXT_SIZE);
        statusBt.setGravity(Gravity.CENTER);
        statusBt.setLayoutParams(new LinearLayout.LayoutParams(0, px(STATUS_BAR_HEIGHT), 1f));

        statusBar.addView(statusBattery);
        statusBar.addView(statusWifi);
        statusBar.addView(statusBt);

        // 时钟主体（避让顶部状态栏）
        clockBody = new LinearLayout(this);
        clockBody.setOrientation(LinearLayout.VERTICAL);
        clockBody.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams cbParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        cbParams.gravity = Gravity.CENTER;
        clockBody.setLayoutParams(cbParams);
        clockBody.setPadding(0, px(STATUS_BAR_HEIGHT + 10), 0, 0);

        timeText = new TextView(this);
        timeText.setTextColor(0xFFFFFFFF);
        setTextPx(timeText, TIME_TEXT_SIZE);
        timeText.setGravity(Gravity.CENTER);
        timeText.setPadding(0, 0, 0, px(8));

        dateText = new TextView(this);
        dateText.setTextColor(0xFFCCCCCC);
        setTextPx(dateText, DATE_TEXT_SIZE);
        dateText.setGravity(Gravity.CENTER);

        clockBody.addView(timeText);
        clockBody.addView(dateText);

        clockPage.addView(statusBar);
        clockPage.addView(clockBody);

        // 2. 应用网格（LauncherScrollView：横向滑动返回时钟页）
        appsScrollView = new LauncherScrollView(this, LauncherScrollView.MODE_H_SWIPE_BACK, gestureListener);
        FrameLayout.LayoutParams appsParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        appsScrollView.setLayoutParams(appsParams);
        appsScrollView.setTranslationX(screenW); // 初始在右侧外

        appsContainer = new LinearLayout(this);
        appsContainer.setOrientation(LinearLayout.VERTICAL);
        appsContainer.setBackgroundColor(0xFF000000);
        appsScrollView.addView(appsContainer);

        // 3. 控制中心（LauncherScrollView：顶部下拉返回时钟页）
        controlPage = new LinearLayout(this);
        controlPage.setOrientation(LinearLayout.VERTICAL);
        controlPage.setBackgroundColor(0xFF000000);
        FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        controlPage.setLayoutParams(controlParams);
        controlPage.setTranslationY(screenH); // 初始在下方外

        controlScroll = new LauncherScrollView(this, LauncherScrollView.MODE_PULL_DOWN, gestureListener);
        controlScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));

        controlContent = new LinearLayout(this);
        controlContent.setOrientation(LinearLayout.VERTICAL);
        controlContent.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        // ---- 控制中心内容 ----
        // 音量
        TextView volLabel = new TextView(this);
        volLabel.setText("音量");
        volLabel.setTextColor(0xFFFFFFFF);
        setTextPx(volLabel, LABEL_TEXT_SIZE);
        volLabel.setGravity(Gravity.CENTER);
        volLabel.setPadding(0, px(LABEL_PADDING_VERTICAL), 0, 0);

        volumeSeek = new SeekBar(this);
        volumeSeek.setMax(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volumeSeek.setProgress(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
        volumeSeek.setPadding(px(SEEK_BAR_PADDING), 0, px(SEEK_BAR_PADDING), 0);
        volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        // 亮度
        TextView brightLabel = new TextView(this);
        brightLabel.setText("亮度");
        brightLabel.setTextColor(0xFFFFFFFF);
        setTextPx(brightLabel, LABEL_TEXT_SIZE);
        brightLabel.setGravity(Gravity.CENTER);
        brightLabel.setPadding(0, px(LABEL_PADDING_VERTICAL * 2), 0, 0);

        brightSeek = new SeekBar(this);
        brightSeek.setMax(255);
        int currentBright = Settings.System.getInt(getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, 100);
        brightSeek.setProgress(Math.max(currentBright, 10));
        brightSeek.setPadding(px(SEEK_BAR_PADDING), 0, px(SEEK_BAR_PADDING), 0);
        brightSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    int val = Math.max(progress, 10);
                    // 调整当前窗口亮度
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = val / 255.0f;
                    getWindow().setAttributes(lp);
                    // 尝试写入系统设置（可能需要系统权限，忽略异常）
                    try {
                        Settings.System.putInt(getContentResolver(),
                                Settings.System.SCREEN_BRIGHTNESS, val);
                    } catch (Exception e) {
                        // 精简系统可能不允许，忽略
                    }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        // WiFi 开关
        wifiToggle = buildToggleRow("Wi-Fi");
        wifiToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                toggleWifi();
            }
        });

        // 蓝牙开关
        btToggle = buildToggleRow("蓝牙");
        btToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                toggleBluetooth();
            }
        });

        // 手电筒开关
        flashToggle = buildToggleRow("手电筒");
        flashToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                toggleFlashlight();
            }
        });

        controlContent.addView(volLabel);
        controlContent.addView(volumeSeek);
        controlContent.addView(brightLabel);
        controlContent.addView(brightSeek);
        controlContent.addView(wifiToggle);
        controlContent.addView(btToggle);
        controlContent.addView(flashToggle);

        controlScroll.addView(controlContent);
        controlPage.addView(controlScroll);

        // 添加所有页面到根布局
        rootLayout.addView(clockPage);
        rootLayout.addView(appsScrollView);
        rootLayout.addView(controlPage);
        setContentView(rootLayout);
    }

    // 开关行（一行一个，240 宽度下比 ToggleButton 可控）
    private TextView buildToggleRow(String label) {
        TextView tv = new TextView(this);
        tv.setText(label + "  --");
        tv.setTextColor(0xFFFFFFFF);
        setTextPx(tv, TOGGLE_TEXT_SIZE);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, px(8), 0, px(8));
        tv.setBackgroundColor(0x1AFFFFFF);
        return tv;
    }

    // ---------- 加载应用 ----------
    private void loadApps() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppInfo> installedApps = getInstalledApps();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        appList = installedApps;
                        buildAppListUI();
                    }
                });
            }
        }).start();
    }

    private List<AppInfo> getInstalledApps() {
        List<AppInfo> result = new ArrayList<AppInfo>();
        PackageManager pm = getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(mainIntent, 0);

        for (ResolveInfo ri : resolveInfos) {
            try {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                AppInfo info = new AppInfo();
                info.name = ri.loadLabel(pm).toString();
                info.packageName = pkg;
                info.icon = ri.loadIcon(pm); // 磁盘 IO，留在后台线程
                info.intent = new Intent(Intent.ACTION_MAIN)
                        .setClassName(pkg, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                result.add(info);
            } catch (Exception e) {
                // 应用可能在加载过程中被卸载，跳过
            }
        }

        Collections.sort(result, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo a, AppInfo b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return result;
    }

    // 网格列数：240→2、360→3、480→4
    private int appColumns() {
        int cols = Math.round(base / 120f);
        return Math.max(2, cols);
    }

    private void buildAppListUI() {
        appsContainer.removeAllViews();
        int cols = appColumns();
        LinearLayout currentRow = null;

        for (int i = 0; i < appList.size(); i++) {
            if (i % cols == 0) {
                currentRow = new LinearLayout(this);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                appsContainer.addView(currentRow);

                // 行间分隔线
                if (i > 0) {
                    View divider = new View(this);
                    divider.setBackgroundColor(0x1AFFFFFF);
                    divider.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1));
                    appsContainer.addView(divider);
                }
            }
            currentRow.addView(buildAppCell(appList.get(i)));
        }
    }

    private View buildAppCell(final AppInfo info) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setLayoutParams(new LinearLayout.LayoutParams(0, px(APP_CELL_HEIGHT), 1f));
        cell.setPadding(px(2), px(4), px(2), px(4));
        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(info.intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法启动", Toast.LENGTH_SHORT).show();
                }
            }
        });

        ImageView icon = new ImageView(this);
        if (info.icon != null) {
            icon.setImageDrawable(info.icon);
        }
        icon.setLayoutParams(new LinearLayout.LayoutParams(px(APP_ICON_SIZE), px(APP_ICON_SIZE)));

        TextView label = new TextView(this);
        label.setText(info.name);
        label.setTextColor(0xFFFFFFFF);
        setTextPx(label, APP_GRID_TEXT_SIZE);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);

        cell.addView(icon);
        cell.addView(label);
        return cell;
    }

    // ---------- 状态栏（电池 / WiFi / 蓝牙） ----------
    private void registerStatusReceiver() {
        if (statusRegistered) return;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            registerReceiver(statusReceiver, filter);
            statusRegistered = true;
        } catch (Exception e) {
            // 极少数 ROM 不允许注册，忽略
        }
    }

    private void unregisterStatusReceiver() {
        if (!statusRegistered) return;
        try {
            unregisterReceiver(statusReceiver);
            statusRegistered = false;
        } catch (Exception e) {
            // 忽略
        }
    }

    private void updateStatusBar() {
        if (statusBattery == null) return;
        statusBattery.setText(batteryLevel >= 0 ? batteryLevel + "%" : "--");
        boolean wifiOn = wifiManager != null && wifiManager.isWifiEnabled();
        statusWifi.setText(wifiOn ? "Wi-Fi" : "");
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        boolean btOn = ba != null && ba.isEnabled();
        statusBt.setText(btOn ? "蓝牙" : "");
    }

    // ---------- 控制中心开关 ----------
    private void toggleWifi() {
        if (wifiManager == null) {
            Toast.makeText(this, "无Wi-Fi", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            wifiManager.setWifiEnabled(!wifiManager.isWifiEnabled());
        } catch (Exception e) {
            Toast.makeText(this, "Wi-Fi不可用", Toast.LENGTH_SHORT).show();
        }
        updateToggleUI();
    }

    private void toggleBluetooth() {
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        if (ba == null) {
            Toast.makeText(this, "无蓝牙", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (ba.isEnabled()) {
                ba.disable();
            } else {
                ba.enable();
            }
        } catch (Exception e) {
            Toast.makeText(this, "蓝牙不可用", Toast.LENGTH_SHORT).show();
        }
        updateToggleUI();
    }

    private void toggleFlashlight() {
        if (flashlightOn) {
            releaseFlashlight();
        } else {
            try {
                camera = Camera.open();
                Camera.Parameters params = camera.getParameters();
                if (params.getSupportedFlashModes() == null
                        || !params.getSupportedFlashModes().contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                    camera.release();
                    camera = null;
                    Toast.makeText(this, "不支持手电筒", Toast.LENGTH_SHORT).show();
                    updateToggleUI();
                    return;
                }
                params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
                camera.setParameters(params);
                camera.startPreview();
                flashlightOn = true;
            } catch (Exception e) {
                releaseFlashlight();
                Toast.makeText(this, "手电筒不可用", Toast.LENGTH_SHORT).show();
            }
        }
        updateToggleUI();
    }

    // 释放相机：onPause/onDestroy 必须调用，否则阻塞其他应用
    private void releaseFlashlight() {
        if (camera != null) {
            try { camera.stopPreview(); } catch (Exception e) { }
            try { camera.release(); } catch (Exception e) { }
            camera = null;
        }
        flashlightOn = false;
        updateToggleUI();
    }

    private void updateToggleUI() {
        if (wifiToggle == null || btToggle == null || flashToggle == null) return;
        boolean wifiOn = wifiManager != null && wifiManager.isWifiEnabled();
        wifiToggle.setText("Wi-Fi  " + (wifiOn ? "开" : "关"));
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        boolean btOn = ba != null && ba.isEnabled();
        btToggle.setText("蓝牙  " + (btOn ? "开" : "关"));
        flashToggle.setText("手电筒  " + (flashlightOn ? "开" : "关"));
    }

    // ---------- 时钟更新（中文日期） ----------
    private void updateClock() {
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat sdfTime = new SimpleDateFormat("HH:mm", Locale.CHINESE);
        SimpleDateFormat sdfDate = new SimpleDateFormat("M月d日 EEE", Locale.CHINESE);
        timeText.setText(sdfTime.format(cal.getTime()));
        dateText.setText(sdfDate.format(cal.getTime()));
    }

    // ---------- 页面切换动画 ----------
    private void goToApps() {
        if (currentPage == PAGE_APPS) return;
        currentPage = PAGE_APPS;
        clockPage.animate().translationX(-screenW)
                .setDuration(200).setInterpolator(new LinearInterpolator()).start();
        appsScrollView.animate().translationX(0)
                .setDuration(200).setInterpolator(new LinearInterpolator()).start();
        appsScrollView.scrollTo(0, 0);
    }

    private void goToClockFromApps() {
        if (currentPage != PAGE_APPS) return;
        currentPage = PAGE_CLOCK;
        appsScrollView.animate().translationX(screenW)
                .setDuration(200).setInterpolator(new LinearInterpolator()).start();
        clockPage.animate().translationX(0)
                .setDuration(200).setInterpolator(new LinearInterpolator()).start();
    }

    private void goToControl() {
        if (currentPage == PAGE_CONTROL) return;
        currentPage = PAGE_CONTROL;
        clockPage.animate().translationY(-screenH)
                .setDuration(200).setInterpolator(new LinearInterpolator()).start();
        controlPage.animate().translationY(0)
                .setDuration(200).setInterpolator(new LinearInterpolator()).start();
    }

    private void goToClockFromControl() {
        if (currentPage != PAGE_CONTROL) return;
        currentPage = PAGE_CLOCK;
        controlPage.animate().translationY(screenH)
                .setDuration(200).setInterpolator(new LinearInterpolator()).start();
        clockPage.animate().translationY(0)
                .setDuration(200).setInterpolator(new LinearInterpolator()).start();
    }

    // ---------- 触摸事件（仅时钟页；应用页/控制页手势由 LauncherScrollView 处理） ----------
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (currentPage != PAGE_CLOCK) return false;

        int action = event.getAction();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                isSliding = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (isSliding) return true;
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                float absDx = Math.abs(dx);
                float absDy = Math.abs(dy);
                int thresh = px(SWIPE_THRESHOLD);

                if (absDx < thresh && absDy < thresh) return true;
                // 方向锁定：首次超过阈值即确定方向
                if (absDx > absDy) {
                    isSliding = true;
                    goToApps();
                } else {
                    isSliding = true;
                    goToControl();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isSliding = false;
                return true;
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        if (currentPage == PAGE_APPS) {
            goToClockFromApps();
        } else if (currentPage == PAGE_CONTROL) {
            goToClockFromControl();
        } else {
            super.onBackPressed();
        }
    }

    // ---------- 手势回调（LauncherScrollView 触发） ----------
    private GestureListener gestureListener = new GestureListener() {
        @Override
        public void onSwipeBack() {
            if (currentPage == PAGE_APPS) {
                goToClockFromApps();
            } else if (currentPage == PAGE_CONTROL) {
                goToClockFromControl();
            }
        }
    };

    // ---------- 自定义滚动视图：解决 ScrollView/子 View 消费事件导致父层手势失效 ----------
    private class LauncherScrollView extends ScrollView {
        // 模式：横滑返回 / 顶部下拉返回
        private static final int MODE_H_SWIPE_BACK = 0;
        private static final int MODE_PULL_DOWN = 1;

        private int mode;
        private GestureListener listener;
        private float downX, downY;
        private boolean swipeTriggered = false;

        public LauncherScrollView(Context context, int mode, GestureListener listener) {
            super(context);
            this.mode = mode;
            this.listener = listener;
            setVerticalScrollBarEnabled(true);
            setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        }

        // onInterceptTouchEvent 每次 MOVE 先被调用，不受子 View 消费影响
        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            int action = ev.getAction();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    swipeTriggered = false;
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (swipeTriggered) break;
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    float absDx = Math.abs(dx);
                    float absDy = Math.abs(dy);
                    int thresh = px(SWIPE_THRESHOLD);

                    if (absDx < thresh && absDy < thresh) break;

                    if (mode == MODE_H_SWIPE_BACK) {
                        // 应用页：横向滑动返回时钟
                        if (absDx > absDy && absDx > thresh) {
                            swipeTriggered = true;
                            if (listener != null) listener.onSwipeBack();
                            return true;
                        }
                    } else { // MODE_PULL_DOWN
                        // 控制页：列表顶部下拉返回时钟
                        if (getScrollY() <= 0 && dy > 0 && absDy > absDx && absDy > thresh) {
                            swipeTriggered = true;
                            if (listener != null) listener.onSwipeBack();
                            return true;
                        }
                    }
                    break;
            }
            return super.onInterceptTouchEvent(ev);
        }
    }
}
