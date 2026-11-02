package com.local.farmreminder;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class CaptureService extends Service {
    public static volatile boolean isRunning = false;
    public static volatile String lastStatus = "未运行";
    public static final String ACTION_STOP = "com.local.farmreminder.STOP";
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread worker;
    private Handler handler;
    private TextRecognizer recognizer;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Object captureLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final RecognitionLogic logic = new RecognitionLogic();
    private long lastFrame;
    private int leadSeconds, width, height;
    private volatile boolean active;
    private volatile int generation;
    private boolean stopping;
    private final MediaProjection.Callback callback = new MediaProjection.Callback() {
        @Override public void onStop() { mainHandler.post(() -> { if (active) { active = false; stopping = true; generation++; isRunning = false; lastStatus = "录屏授权已结束"; stopSelf(); } }); }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { active = false; stopping = true; generation++; isRunning = false; lastStatus = "已停止"; stopSelf(); return START_NOT_STICKY; }
        if (active || stopping) return START_NOT_STICKY;
        startForeground(1, foregroundNotification());
        if (intent == null) { lastStatus = "缺少录屏授权"; stopSelf(); return START_NOT_STICKY; }
        int code = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data = android.os.Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra("data", Intent.class) : intent.getParcelableExtra("data");
        if (code != Activity.RESULT_OK || data == null) { lastStatus = "未获得录屏授权"; stopSelf(); return START_NOT_STICKY; }
        leadSeconds = intent.getIntExtra("leadSeconds", 60);
        try {
            worker = new HandlerThread("capture"); worker.start(); handler = new Handler(worker.getLooper());
            recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            DisplayMetrics metrics = new DisplayMetrics();
            ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
            width = Math.min(metrics.widthPixels, 1080); height = Math.max(1, metrics.heightPixels * width / metrics.widthPixels);
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(this::onImage, handler);
            projection = ((MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE)).getMediaProjection(code, data);
            if (projection == null) throw new IllegalStateException("授权无效");
            projection.registerCallback(callback, handler);
            active = true; isRunning = true;
            display = projection.createVirtualDisplay("FarmCapture", width, height, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
            lastStatus = "正在识别，等待稳定画面";
        } catch (Exception e) { lastStatus = "启动截屏失败: " + e.getMessage(); stopSelf(); }
        return START_NOT_STICKY;
    }

    private Notification foregroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("capture", "截屏识别", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "capture").setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("农场识别运行中").setContentText("仅在本机识别画面").setContentIntent(open).build();
    }

    private void onImage(ImageReader source) {
        synchronized (captureLock) {
        Image image = null; Bitmap padded = null, bitmap = null;
        final int frameGeneration = generation;
        try {
            if (!active) return;
            image = source.acquireLatestImage(); if (image == null) return;
            long elapsed = SystemClock.elapsedRealtime();
            if (!active || elapsed - lastFrame < 1000 || !busy.compareAndSet(false, true)) return;
            lastFrame = elapsed; long capturedAt = System.currentTimeMillis();
            Image.Plane plane = image.getPlanes()[0]; ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride(), rowStride = plane.getRowStride();
            int paddedWidth = width + (rowStride - pixelStride * width) / pixelStride;
            padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888); padded.copyPixelsFromBuffer(buffer);
            bitmap = Bitmap.createBitmap(padded, 0, 0, width, height);
            if (bitmap != padded) padded.recycle(); padded = null;
            final Bitmap frame = bitmap;
            recognizer.process(InputImage.fromBitmap(frame, 0)).addOnCompleteListener(task -> {
                try {
                    if (!active || generation != frameGeneration) return;
                    if (!task.isSuccessful() || task.getResult() == null) { logic.reset(); lastStatus = "识别失败，等待下一帧"; return; }
                    processText(task.getResult(), capturedAt);
                } finally { frame.recycle(); busy.set(false); }
            });
            bitmap = null;
        } catch (Exception e) { mainHandler.post(() -> { if (active && generation == frameGeneration) { logic.reset(); lastStatus = "截帧失败: " + e.getMessage(); } }); busy.set(false); }
        finally { if (image != null) image.close(); if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); if (padded != null && !padded.isRecycled()) padded.recycle(); }
        }
    }
    private void processText(Text text, long capturedAt) {
        List<OcrLine> lines = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) for (Text.Line line : block.getLines()) {
            Rect box = line.getBoundingBox();
            if (box != null) lines.add(new OcrLine(line.getText(), box.left, box.top, box.right, box.bottom));
        }
        String friend = OcrFrameParser.friend(lines, width, height);
        Long countdown = OcrFrameParser.countdown(lines);
        Long maturity = logic.observe(friend, countdown, capturedAt);
        if (friend == null) lastStatus = "未确认好友，未保存";
        else if (countdown == null) lastStatus = "未找到明确成熟倒计时";
        else if (maturity == null) lastStatus = "等待连续帧确认: " + friend;
        else {
            Reminder r = new ReminderStore(this).addIfNew(friend, maturity, leadSeconds);
            if (r != null) new AlarmScheduler(this).schedule(r);
            lastStatus = r == null ? "已记录: " + friend : "已记录: " + friend + (r.triggerAt <= System.currentTimeMillis() ? "（提醒时间已过）" : new AlarmScheduler(this).canScheduleExact() ? "" : "（非精确提醒）");
        }
    }
    @Override public void onDestroy() {
        active = false; stopping = true; generation++; isRunning = false; logic.reset();
        synchronized (captureLock) {
        if (reader != null) { reader.setOnImageAvailableListener(null, null); reader.close(); reader = null; }
        if (display != null) { display.release(); display = null; }
        if (projection != null) { projection.unregisterCallback(callback); projection.stop(); projection = null; }
        if (recognizer != null) { recognizer.close(); recognizer = null; }
        }
        if (worker != null) { worker.quitSafely(); worker = null; }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
