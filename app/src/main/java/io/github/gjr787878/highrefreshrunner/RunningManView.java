package io.github.gjr787878.highrefreshrunner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import java.util.Locale;

/**
 * 奔跑小人动画视图：Canvas 逐帧绘制。
 * 每一帧 onDraw 末尾 invalidate() 触发下一帧 —— 持续渲染是维持峰值刷新率的必要条件（配合 KEEP_SCREEN_ON）。
 * 同时统计 FPS，并显示实时刷新率（Hz）。
 */
public class RunningManView extends View {

    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudMain = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudSub = new Paint(Paint.ANTI_ALIAS_FLAG);

    private long startUptime = SystemClock.uptimeMillis();
    private boolean running = true;
    private boolean showFps = true;
    private float speed = 6.8f; // 奔跑循环角速度 rad/s

    // FPS 统计
    private int frames = 0;
    private long fpsWindowStart = 0;
    private float fps = 0f;

    public RunningManView(Context context) {
        super(context);
        bodyPaint.setStyle(Paint.Style.STROKE);
        bodyPaint.setStrokeCap(Paint.Cap.ROUND);
        bodyPaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        groundPaint.setStyle(Paint.Style.STROKE);
        groundPaint.setStrokeCap(Paint.Cap.ROUND);
        hudMain.setStyle(Paint.Style.FILL);
        hudMain.setColor(Color.WHITE);
        hudMain.setShadowLayer(6f, 0f, 0f, 0xAA000000);
        hudSub.setStyle(Paint.Style.FILL);
        hudSub.setColor(0xFF9AD8FF);
        hudSub.setShadowLayer(6f, 0f, 0f, 0xAA000000);
    }

    public void setRunning(boolean r) {
        running = r;
        invalidate();
    }

    public void setShowFps(boolean s) {
        showFps = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            if (running) invalidate();
            return;
        }

        // FPS 统计（每 500ms 滑动窗口）
        frames++;
        if (fpsWindowStart == 0) fpsWindowStart = now;
        long elapsed = now - fpsWindowStart;
        if (elapsed >= 500) {
            fps = frames * 1000f / elapsed;
            frames = 0;
            fpsWindowStart = now;
        }

        canvas.drawColor(0xFF0B0D12); // 动画区深蓝黑底
        float groundY = h * 0.78f;
        float cx = w / 2f;
        float t = (now - startUptime) / 1000f;

        drawGround(canvas, w, groundY, t);
        drawRunner(canvas, cx, groundY, h, t);

        if (showFps) drawHud(canvas);

        if (running) invalidate(); // 持续渲染（关键：每帧请求下一帧，维持峰值刷新率）
    }

    /** 奔跑小人：圆头 + 躯干 + 双臂双腿（两段式，相位 π 错开），带身体起伏与前倾 */
    private void drawRunner(Canvas c, float cx, float groundY, float h, float t) {
        float H = groundY * 0.60f;
        float sw = h * 0.022f;
        bodyPaint.setStrokeWidth(sw);
        bodyPaint.setColor(Color.WHITE);

        float phase = t * speed;
        float bounce = Math.abs((float) Math.sin(phase)) * H * 0.035f; // 身体起伏
        float lean = 0.12f;                                             // 前倾

        float hipX = cx + (float) Math.sin(phase) * H * 0.03f;
        float hipY = groundY - H * 0.42f - bounce;
        float shX = hipX + (float) Math.sin(lean) * H * 0.30f;
        float shY = hipY - H * 0.24f;
        float headCx = shX + (float) Math.sin(lean) * H * 0.16f;
        float headCy = shY - H * 0.13f;
        float headR = H * 0.095f;

        // 头：强调蓝填充 + 白描边
        fillPaint.setColor(0xFF0A84FF);
        c.drawCircle(headCx, headCy, headR, fillPaint);
        bodyPaint.setStrokeWidth(sw * 0.9f);
        c.drawCircle(headCx, headCy, headR, bodyPaint);

        // 躯干（肩 → 髋）
        bodyPaint.setStrokeWidth(sw);
        c.drawLine(shX, shY, hipX, hipY, bodyPaint);

        float legLen = H * 0.30f;
        float armLen = H * 0.24f;

        // 双腿：相位 π 错开（一前一后），髋 → 膝 → 脚
        for (int i = 0; i < 2; i++) {
            float p = phase + i * (float) Math.PI;
            float footAng = (float) Math.sin(p) * 0.85f;
            float kneeAng = (float) Math.sin(p + 0.6f) * 0.45f + 0.35f;
            float kx = hipX + (float) Math.sin(kneeAng) * legLen * 0.55f;
            float ky = hipY + (float) Math.cos(kneeAng) * legLen * 0.55f;
            float fx = hipX + (float) Math.sin(footAng) * legLen;
            float fy = Math.min(groundY - h * 0.005f, hipY + (float) Math.cos(footAng) * legLen);
            c.drawLine(hipX, hipY, kx, ky, bodyPaint);
            c.drawLine(kx, ky, fx, fy, bodyPaint);
        }

        // 双臂：与腿反相（同侧手腿交替），肩 → 肘 → 手
        for (int i = 0; i < 2; i++) {
            float p = phase + i * (float) Math.PI + (float) Math.PI;
            float handAng = (float) Math.sin(p) * 0.8f;
            float elbAng = (float) Math.sin(p + 0.7f) * 0.4f;
            float ex = shX + (float) Math.sin(elbAng) * armLen * 0.5f;
            float ey = shY + (float) Math.cos(elbAng) * armLen * 0.5f;
            float hx = shX + (float) Math.sin(handAng) * armLen;
            float hy = shY + (float) Math.cos(handAng) * armLen;
            c.drawLine(shX, shY, ex, ey, bodyPaint);
            c.drawLine(ex, ey, hx, hy, bodyPaint);
        }
    }

    /** 地面刻度向左移动 + 脚步尘土，营造奔跑速度感 */
    private void drawGround(Canvas c, float w, float groundY, float t) {
        groundPaint.setStrokeWidth(Math.max(2f, w * 0.004f));
        groundPaint.setColor(0x66FFFFFF);
        float gap = w / 6f;
        float offset = (t * speed * 30f) % gap;
        for (float x = -offset - gap; x < w + gap; x += gap) {
            float alpha = 1f - Math.min(1f, Math.abs(x - w / 2f) / (w / 2f));
            groundPaint.setAlpha((int) (80 + 130 * alpha));
            c.drawLine(x, groundY, x, groundY + w * 0.02f, groundPaint);
        }
        groundPaint.setAlpha(70);
        float phase = t * speed;
        for (int i = 0; i < 4; i++) {
            float dp = (float) ((phase * 0.5 + i * 1.7) % (2 * Math.PI));
            float dx = (float) Math.sin(dp) * w * 0.10f;
            float dy = (float) Math.abs(Math.cos(dp)) * groundY * 0.03f;
            float r = w * 0.006f * (1 + (float) Math.cos(dp));
            c.drawCircle(w / 2f + dx, groundY - dy, r, groundPaint);
        }
    }

    /** 左上角 HUD：实时刷新率（Hz）+ FPS */
    private void drawHud(Canvas c) {
        hudMain.setTextSize(Math.min(getHeight() * 0.06f, 56f));
        hudMain.setFakeBoldText(true);
        c.drawText(currentRefreshLabel(), getWidth() * 0.04f, getHeight() * 0.08f, hudMain);
        hudMain.setFakeBoldText(false);

        hudSub.setTextSize(Math.min(getHeight() * 0.035f, 30f));
        c.drawText(String.format(Locale.US, "%.1f FPS", fps), getWidth() * 0.04f, getHeight() * 0.14f, hudSub);
    }

    private String currentRefreshLabel() {
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        float rate = wm.getDefaultDisplay().getMode().getRefreshRate();
        return String.format(Locale.US, "%.0f Hz", rate);
    }
}
