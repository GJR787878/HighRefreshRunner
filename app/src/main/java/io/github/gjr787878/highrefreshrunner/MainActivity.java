package io.github.gjr787878.highrefreshrunner;

// §0.5 #11 / §3.6.1 导航栏磨砂：上白下透渐变（GlassButtons v1.0.5 GlassNavBar 已内置 0xF06A6A72 → 0x882C2C2E）
// §3.4 平板适配：smallestScreenWidthDp >= 600 → 左侧竖排悬浮胶囊导航，内容区让出约 104dp（§10.1）
// §3.6 所有开关用 GlassCapsuleButton；§6 #35 必须 import com.gjr.glassbutton.*

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.gjr.glassbutton.GlassCapsuleButton;
import com.gjr.glassbutton.GlassNavBar;

import java.util.Locale;

/**
 * 高刷奔跑：让屏幕以最高刷新率运行 + 奔跑小人动画。
 * <p>
 * 高刷原理：
 * 1. {@link WindowManager.LayoutParams#preferredDisplayModeId} 请求峰值显示模式（API 23+）
 * 2. API 34+ 同时设 preferredRefreshRate 精确指定刷新率（Android 14，AOSP 确认字段存在）
 * 3. watchdog 每 2 秒重断言，防止省电策略把刷新率降回 60Hz
 * 4. RunningManView 逐帧 invalidate 保持持续渲染，维持峰值刷新率
 */
public class MainActivity extends Activity {

    private static final long WATCHDOG_MS = 2000L;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private RunningManView runnerView;
    private TextView statusLine;
    private LinearLayout modeList;
    private TextView curModeText;

    private boolean forceHigh = true;
    private boolean showFps = true;
    private boolean tablet;

    private Display.Mode peakMode;
    private View panelAnim, panelInfo, panelSetting;

    /** 高刷 watchdog：每 2s 重断言峰值刷新率（防降频） */
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            applyPeakRefresh();
            handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    /** 状态行刷新 */
    private final Runnable hudLoop = new Runnable() {
        @Override public void run() {
            updateStatusLine();
            handler.postDelayed(this, 500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("hrf_config", MODE_PRIVATE);
        // loadConfig（§3.6 B）：进入页面按持久化值恢复全部状态
        forceHigh = prefs.getBoolean("force_high", true);
        showFps = prefs.getBoolean("show_fps", true);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // 常亮 + 持续渲染
        tablet = getResources().getConfiguration().smallestScreenWidthDp >= 600; // §3.4 平板断点
        peakMode = findBestMode();

        buildUi();
        applyPeakRefresh();
        handler.postDelayed(watchdog, WATCHDOG_MS);
        handler.postDelayed(hudLoop, 500L);
    }

    // ==================== UI 构建 ====================

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000); // §3.1 全局深色纯黑底

        // 内容层：full-bleed 铺满（含导航背后），防止半透明玻璃透出黑块（§10.1）
        FrameLayout contentHost = new FrameLayout(this);
        panelAnim = buildAnimPanel();
        panelInfo = buildInfoPanel();
        panelSetting = buildSettingPanel();
        contentHost.addView(panelAnim, new FrameLayout.LayoutParams(MATCH, MATCH));
        contentHost.addView(panelInfo, new FrameLayout.LayoutParams(MATCH, MATCH));
        contentHost.addView(panelSetting, new FrameLayout.LayoutParams(MATCH, MATCH));
        switchPanel(0);
        root.addView(contentHost, new FrameLayout.LayoutParams(MATCH, MATCH));

        // 导航层：悬浮于内容之上
        GlassNavBar nav = new GlassNavBar(this);
        nav.addItem(icon(R.drawable.ic_nav_run), "动画");
        nav.addItem(icon(R.drawable.ic_nav_info), "信息");
        nav.addItem(icon(R.drawable.ic_nav_setting), "设置");
        nav.setSelected(0);
        nav.setOnItemSelectedListener(index -> switchPanel(index)); // §8 GlassNavBar 用法

        FrameLayout.LayoutParams navLp;
        if (tablet) {
            // §3.4/§3.6.1 平板：左侧竖排悬浮胶囊（垂直居中、约半屏高）；渐变方向随 setOrientation 自动切 LEFT_RIGHT
            nav.setOrientation(LinearLayout.VERTICAL);
            nav.setSideWidthDp(72f);
            navLp = new FrameLayout.LayoutParams(WRAP, getResources().getDisplayMetrics().heightPixels / 2);
            navLp.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
            navLp.leftMargin = dp(20);
        } else {
            // 手机：底部横排（默认 TOP_BOTTOM 渐变）
            navLp = new FrameLayout.LayoutParams(MATCH, WRAP);
            navLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            navLp.leftMargin = dp(16);
            navLp.rightMargin = dp(16);
            navLp.bottomMargin = dp(16);
        }
        root.addView(nav, navLp);

        setContentView(root);
    }

    private View buildAnimPanel() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int lr = tablet ? dp(104) : dp(24); // §10.1 平板内容区让出左侧导航宽度
        // §3.7 根布局 padding：顶 48 / 左右 24 / 底 32；手机底部额外让出导航（§10.1 112dp）
        col.setPadding(lr, dp(48), dp(24), tablet ? dp(32) : dp(112));

        TextView title = makeTitle("高刷奔跑");
        col.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView sub = makeSection("屏幕以最高刷新率运行 · 奔跑小人动画");
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        subLp.topMargin = dp(8);
        subLp.bottomMargin = dp(16);
        col.addView(sub, subLp);

        runnerView = new RunningManView(this);
        runnerView.setShowFps(showFps);
        col.addView(runnerView, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        statusLine = new TextView(this);
        statusLine.setTextColor(0xFF9AD8FF);
        statusLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(MATCH, WRAP);
        slp.topMargin = dp(12);
        col.addView(statusLine, slp);
        return col;
    }

    private View buildInfoPanel() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int lr = tablet ? dp(104) : dp(24);
        col.setPadding(lr, dp(48), dp(24), tablet ? dp(32) : dp(112));
        sv.addView(col, new ScrollView.LayoutParams(MATCH, WRAP));

        col.addView(makeTitle("刷新率信息"), new LinearLayout.LayoutParams(MATCH, WRAP));

        col.addView(makeSection("当前显示模式"), new LinearLayout.LayoutParams(MATCH, WRAP));
        curModeText = makeValue("");
        LinearLayout.LayoutParams curLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        curLp.topMargin = dp(6);
        col.addView(curModeText, curLp);

        col.addView(makeSection("设备支持的全部显示模式"), new LinearLayout.LayoutParams(MATCH, WRAP));
        modeList = new LinearLayout(this);
        modeList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        listLp.topMargin = dp(6);
        col.addView(modeList, listLp);
        fillModeList();

        TextView note = makeSection("说明：最高刷新率受设备能力限制；部分设备需在系统设置中开启高刷（如 120Hz）。"
                + "本应用通过 preferredDisplayModeId 请求峰值模式，并持续渲染保持峰值刷新率。");
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        noteLp.topMargin = dp(20);
        col.addView(note, noteLp);
        return sv;
    }

    private View buildSettingPanel() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int lr = tablet ? dp(104) : dp(24);
        col.setPadding(lr, dp(48), dp(24), tablet ? dp(32) : dp(112));
        sv.addView(col, new ScrollView.LayoutParams(MATCH, WRAP));

        col.addView(makeTitle("设置"), new LinearLayout.LayoutParams(MATCH, WRAP));

        // §10.6 描述 + 开关纵向交替：描述行在上、整行胶囊在下
        addSettingRow(col, "强制最高刷新率", forceHigh, (btn, next) -> {
            forceHigh = next;
            prefs.edit().putBoolean("force_high", next).apply();
            if (next) {
                applyPeakRefresh();
            } else {
                releasePeakRefresh();
            }
        });
        addSettingRow(col, "显示 FPS", showFps, (btn, next) -> {
            showFps = next;
            if (runnerView != null) runnerView.setShowFps(next);
            prefs.edit().putBoolean("show_fps", next).apply();
        });

        col.addView(makeSection("关于"), new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView about = makeValue(String.format(Locale.CHINA, "高刷奔跑 v%s（%d）\nAndroid %s · %s",
                versionName(), versionCode(), Build.VERSION.RELEASE, Build.MODEL));
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        abLp.topMargin = dp(6);
        col.addView(about, abLp);

        TextView repo = makeValue("仓库 github.com/GJR787878/HighRefreshRunner");
        repo.setTextColor(0xFF9AD8FF);
        LinearLayout.LayoutParams rpLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        rpLp.topMargin = dp(8);
        col.addView(repo, rpLp);
        return sv;
    }

    /** 开关行：标题在上、整行毛玻璃胶囊在下（§3.6 开关三步：读状态 → 翻转写持久化 → 更新 UI） */
    private void addSettingRow(LinearLayout col, String label, boolean initial, SettingAction action) {
        TextView desc = makeSection(label);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        dLp.topMargin = dp(20);
        dLp.bottomMargin = dp(12);
        col.addView(desc, dLp);

        GlassCapsuleButton btn = new GlassCapsuleButton(this); // §8 组件，禁止原生 Switch（§3.6）
        btn.setText(initial ? "开" : "关");
        btn.setGlassSelected(initial);
        btn.setOnClickListener(v -> {
            boolean next = !btn.isGlassSelected();
            btn.setGlassSelected(next);
            btn.setText(next ? "开" : "关");
            action.onToggle(btn, next);
        });
        col.addView(btn, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void fillModeList() {
        Display d = getWindowManager().getDefaultDisplay();
        Display.Mode current = d.getMode();
        curModeText.setText(String.format(Locale.US, "%dx%d @ %.2fHz（当前）",
                current.getPhysicalWidth(), current.getPhysicalHeight(), current.getRefreshRate()));
        Display.Mode[] modes = d.getSupportedModes();
        if (modes == null || modes.length == 0) return;
        for (Display.Mode m : modes) {
            boolean isPeak = peakMode != null && m.getModeId() == peakMode.getModeId();
            TextView tv = makeValue(String.format(Locale.US, "%dx%d @ %.2fHz%s",
                    m.getPhysicalWidth(), m.getPhysicalHeight(), m.getRefreshRate(), isPeak ? "  ← 峰值" : ""));
            if (isPeak) tv.setTextColor(0xFF0A84FF); // 强调蓝 #0A84FF（§0.5）
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
            lp.topMargin = dp(6);
            modeList.addView(tv, lp);
        }
    }

    private void switchPanel(int index) {
        panelAnim.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        panelInfo.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        panelSetting.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
    }

    // ==================== 最高刷新率 ====================

    /** 请求峰值显示模式（核心：preferredDisplayModeId，API 23+；API 34+ 追加 preferredRefreshRate） */
    private void applyPeakRefresh() {
        if (!forceHigh || peakMode == null) return;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.preferredDisplayModeId = peakMode.getModeId();
        if (Build.VERSION.SDK_INT >= 34) {
            lp.preferredRefreshRate = peakMode.getRefreshRate(); // API 34+
        }
        getWindow().setAttributes(lp);
    }

    private void releasePeakRefresh() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.preferredDisplayModeId = 0;
        if (Build.VERSION.SDK_INT >= 34) {
            lp.preferredRefreshRate = 0f;
        }
        getWindow().setAttributes(lp);
    }

    /** 找最高刷新率模式：优先同分辨率（不切分辨率避免布局错乱），兜底全局最高刷新率 */
    private Display.Mode findBestMode() {
        Display d = getWindowManager().getDefaultDisplay();
        Display.Mode[] modes = d.getSupportedModes();
        if (modes == null || modes.length == 0) return null;
        Display.Mode current = d.getMode();
        Display.Mode best = null;
        for (Display.Mode m : modes) {
            if (m.getPhysicalWidth() == current.getPhysicalWidth()
                    && m.getPhysicalHeight() == current.getPhysicalHeight()
                    && (best == null || m.getRefreshRate() > best.getRefreshRate())) {
                best = m;
            }
        }
        if (best == null) {
            for (Display.Mode m : modes) {
                if (best == null || m.getRefreshRate() > best.getRefreshRate()) best = m;
            }
        }
        return best;
    }

    private void updateStatusLine() {
        if (statusLine == null) return;
        Display.Mode cur = getWindowManager().getDefaultDisplay().getMode();
        String s = String.format(Locale.US, "当前 %dx%d @ %.2fHz",
                cur.getPhysicalWidth(), cur.getPhysicalHeight(), cur.getRefreshRate());
        if (peakMode != null) {
            s += String.format(Locale.US, " · 峰值 %dx%d @ %.2fHz",
                    peakMode.getPhysicalWidth(), peakMode.getPhysicalHeight(), peakMode.getRefreshRate());
            s += forceHigh ? " · 已锁定" : " · 未锁定";
        }
        statusLine.setText(s);
    }

    // ==================== 工具 ====================

    private TextView makeTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        return tv;
    }

    private TextView makeSection(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFCCCCCC);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        return tv;
    }

    private TextView makeValue(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        return tv;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private Drawable icon(int res) {
        Drawable d = getDrawable(res);
        d.mutate(); // 会被 GlassNavBar 着色
        return d;
    }

    private String versionName() {
        try {
            PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
            return p.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private long versionCode() {
        try {
            PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return p.getLongVersionCode();
            return p.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(watchdog);
        handler.removeCallbacks(hudLoop);
    }

    private interface SettingAction {
        void onToggle(GlassCapsuleButton btn, boolean next);
    }
}
