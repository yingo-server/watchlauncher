package com.teemo.launcher;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
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

    // 页面状态
    private static final int PAGE_CLOCK = 0;
    private static final int PAGE_APPS = 1;
    private static final int PAGE_CONTROL = 2;

    // ---------- 针对240x240的精确像素值 ----------
    private static final int SCREEN_W = 240;
    private static final int SCREEN_H = 240;

    // 时钟字体
    private static final int TIME_TEXT_SIZE = 48;     // 像素
    private static final int DATE_TEXT_SIZE = 22;     // 像素

    // 应用列表
    private static final int APP_ITEM_HEIGHT = 48;    // 像素（240/5 = 48）
    private static final int APP_TEXT_SIZE = 32;      // 像素（一行7个汉字 ≈ 224px，留出边距）
    private static final int APP_LEFT_PADDING = 15;   // 像素

    // 控制中心
    private static final int LABEL_TEXT_SIZE = 18;    // 像素
    private static final int SEEK_BAR_PADDING = 20;   // 像素（左右边距）
    private static final int LABEL_PADDING_VERTICAL = 8; // 像素（标签上下内边距）

    // 手势
    private static final int SWIPE_THRESHOLD = 30;    // 像素（滑动触发阈值）

    // 根布局
    private FrameLayout rootLayout;

    // 页面容器
    private LinearLayout clockPage;
    private ScrollView appsScrollView;
    private LinearLayout appsContainer;
    private LinearLayout controlPage;

    // 时钟控件
    private TextView timeText;
    private TextView dateText;

    // 控制中心
    private SeekBar volumeSeek;
    private SeekBar brightSeek;
    private AudioManager audioManager;

    // 手势
    private float downX, downY;
    private int currentPage = PAGE_CLOCK;
    private boolean isSliding = false;
    private boolean isTouchingSeekBar = false;

    // 时钟更新
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

        // 初始化音频
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // 构建UI（所有尺寸直接使用像素值）
        buildUI();

        // 加载应用列表
        loadApps();

        // 更新时钟
        updateClock();
        clockHandler.postDelayed(clockRunnable, 1000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        clockHandler.postDelayed(clockRunnable, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        clockHandler.removeCallbacks(clockRunnable);
    }

    // ---------- 构建UI ----------
    private void buildUI() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(0xFF000000);
        rootLayout.setOnTouchListener(this);

        // 1. 时钟页面
        clockPage = new LinearLayout(this);
        clockPage.setOrientation(LinearLayout.VERTICAL);
        clockPage.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams clockParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        clockPage.setLayoutParams(clockParams);

        timeText = new TextView(this);
        timeText.setTextColor(0xFFFFFFFF);
        timeText.setTextSize(TIME_TEXT_SIZE); // 直接像素值
        timeText.setGravity(Gravity.CENTER);
        timeText.setPadding(0, 0, 0, 8); // 上下间距8px

        dateText = new TextView(this);
        dateText.setTextColor(0xFFCCCCCC);
        dateText.setTextSize(DATE_TEXT_SIZE);
        dateText.setGravity(Gravity.CENTER);

        clockPage.addView(timeText);
        clockPage.addView(dateText);

        // 2. 应用列表
        appsScrollView = new ScrollView(this);
        appsScrollView.setVerticalScrollBarEnabled(true);
        appsScrollView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        FrameLayout.LayoutParams appsParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        appsScrollView.setLayoutParams(appsParams);
        appsScrollView.setTranslationX(SCREEN_W); // 初始在右侧外

        appsContainer = new LinearLayout(this);
        appsContainer.setOrientation(LinearLayout.VERTICAL);
        appsContainer.setBackgroundColor(0xFF000000);
        appsScrollView.addView(appsContainer);

        // 3. 控制中心
        controlPage = new LinearLayout(this);
        controlPage.setOrientation(LinearLayout.VERTICAL);
        controlPage.setBackgroundColor(0xFF000000);
        controlPage.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        controlPage.setLayoutParams(controlParams);
        controlPage.setTranslationY(SCREEN_H); // 初始在下方外

        // ---- 控制中心内容 ----
        // 音量标签
        TextView volLabel = new TextView(this);
        volLabel.setText("音量");
        volLabel.setTextColor(0xFFFFFFFF);
        volLabel.setTextSize(LABEL_TEXT_SIZE);
        volLabel.setGravity(Gravity.CENTER);
        volLabel.setPadding(0, LABEL_PADDING_VERTICAL, 0, LABEL_PADDING_VERTICAL);

        // 音量滑动条
        volumeSeek = new SeekBar(this);
        volumeSeek.setMax(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volumeSeek.setProgress(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
        volumeSeek.setPadding(SEEK_BAR_PADDING, 0, SEEK_BAR_PADDING, 0);
        volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { isTouchingSeekBar = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { isTouchingSeekBar = false; }
        });

        // 亮度标签
        TextView brightLabel = new TextView(this);
        brightLabel.setText("亮度");
        brightLabel.setTextColor(0xFFFFFFFF);
        brightLabel.setTextSize(LABEL_TEXT_SIZE);
        brightLabel.setGravity(Gravity.CENTER);
        brightLabel.setPadding(0, LABEL_PADDING_VERTICAL * 2, 0, LABEL_PADDING_VERTICAL);

        // 亮度滑动条
        brightSeek = new SeekBar(this);
        brightSeek.setMax(255);
        int currentBright = Settings.System.getInt(getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, 100);
        brightSeek.setProgress(Math.max(currentBright, 10));
        brightSeek.setPadding(SEEK_BAR_PADDING, 0, SEEK_BAR_PADDING, 0);
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
            @Override public void onStartTrackingTouch(SeekBar seekBar) { isTouchingSeekBar = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { isTouchingSeekBar = false; }
        });

        controlPage.addView(volLabel);
        controlPage.addView(volumeSeek);
        controlPage.addView(brightLabel);
        controlPage.addView(brightSeek);

        // 添加所有页面到根布局
        rootLayout.addView(clockPage);
        rootLayout.addView(appsScrollView);
        rootLayout.addView(controlPage);
        setContentView(rootLayout);
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
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            AppInfo info = new AppInfo();
            info.name = ri.loadLabel(pm).toString();
            info.packageName = pkg;
            info.intent = new Intent(Intent.ACTION_MAIN)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            result.add(info);
        }

        Collections.sort(result, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo a, AppInfo b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return result;
    }

    private void buildAppListUI() {
        appsContainer.removeAllViews();

        for (final AppInfo info : appList) {
            TextView tv = new TextView(this);
            tv.setText(info.name);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(APP_TEXT_SIZE);
            tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            tv.setPadding(APP_LEFT_PADDING, 0, 0, 0);
            tv.setHeight(APP_ITEM_HEIGHT);
            tv.setBackgroundColor(0x00000000);
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        startActivity(info.intent);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "无法启动", Toast.LENGTH_SHORT).show();
                    }
                }
            });

            // 分割线
            View divider = new View(this);
            divider.setBackgroundColor(0x33FFFFFF);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));

            appsContainer.addView(tv);
            appsContainer.addView(divider);
        }
    }

    // ---------- 时钟更新 ----------
    private void updateClock() {
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat sdfTime = new SimpleDateFormat("HH:mm", Locale.US);
        SimpleDateFormat sdfDate = new SimpleDateFormat("MMM dd EEE", Locale.US);
        timeText.setText(sdfTime.format(cal.getTime()));
        dateText.setText(sdfDate.format(cal.getTime()));
    }

    // ---------- 页面切换动画 ----------
    private void goToApps() {
        if (currentPage == PAGE_APPS) return;
        currentPage = PAGE_APPS;
        clockPage.animate().translationX(-SCREEN_W)
                .setDuration(200).setInterpolator(new LinearInterpolator()).start();
        appsScrollView.animate().translationX(0)
                .setDuration(200).setInterpolator(new LinearInterpolator()).start();
        appsScrollView.scrollTo(0, 0);
    }

    private void goToClockFromApps() {
        if (currentPage != PAGE_APPS) return;
        currentPage = PAGE_CLOCK;
        appsScrollView.animate().translationX(SCREEN_W)
                .setDuration(200).setInterpolator(new LinearInterpolator()).start();
        clockPage.animate().translationX(0)
                .setDuration(200).setInterpolator(new LinearInterpolator()).start();
    }

    private void goToControl() {
        if (currentPage == PAGE_CONTROL) return;
        currentPage = PAGE_CONTROL;
        clockPage.animate().translationY(-SCREEN_H)
                .setDuration(200).setInterpolator(new LinearInterpolator()).start();
        controlPage.animate().translationY(0)
                .setDuration(200).setInterpolator(new LinearInterpolator()).start();
    }

    private void goToClockFromControl() {
        if (currentPage != PAGE_CONTROL) return;
        currentPage = PAGE_CLOCK;
        controlPage.animate().translationY(SCREEN_H)
                .setDuration(200).setInterpolator(new LinearInterpolator()).start();
        clockPage.animate().translationY(0)
                .setDuration(200).setInterpolator(new LinearInterpolator()).start();
    }

    // ---------- 触摸事件 ----------
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (isTouchingSeekBar) {
            return false;
        }

        int action = event.getAction();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                isSliding = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                float absDx = Math.abs(dx);
                float absDy = Math.abs(dy);

                if (absDx < SWIPE_THRESHOLD && absDy < SWIPE_THRESHOLD) {
                    return true;
                }
                if (isSliding) return true;

                if (currentPage == PAGE_CLOCK) {
                    if (absDx > absDy) {
                        isSliding = true;
                        goToApps();
                    } else {
                        isSliding = true;
                        goToControl();
                    }
                } else if (currentPage == PAGE_APPS) {
                    if (absDx > absDy && absDx > SWIPE_THRESHOLD) {
                        isSliding = true;
                        goToClockFromApps();
                    } else {
                        // 垂直滑动列表
                        appsScrollView.scrollBy(0, (int) -dy);
                        downY = event.getY();
                    }
                } else if (currentPage == PAGE_CONTROL) {
                    if (absDy > absDx && absDy > SWIPE_THRESHOLD) {
                        isSliding = true;
                        goToClockFromControl();
                    }
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
}
