package io.aodbridge.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.aodbridge.common.Bridge;

/** 前台服务：每分钟取一次数，把结果广播给 SystemUI 里的模块进程。 */
public class PollService extends Service {
    private static final String CHANNEL_ID = "aodbridge";
    private static final int NOTIFICATION_ID = 1;

    private volatile boolean running;
    private Thread worker;
    private volatile String lastGoodText = "";
    private volatile String lastGoodStamp = "";

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("正在连接中转…"));
        if (!running) {
            running = true;
            worker = new Thread(this::loop, "aodbridge-poll");
            worker.start();
        }
        return START_STICKY;
    }

    private void loop() {
        while (running) {
            String status;
            try {
                JSONObject payload = RelayClient.fetchUsage(
                        AppConfig.url(this), AppConfig.token(this), AppConfig.range(this));
                String text = UsageText.build(payload, AppConfig.linesMask(this));
                if (text.isEmpty()) {
                    // 勾选全部落空或旧版中转：退回服务端拼好的整段文本
                    text = payload.optString("text", "");
                }
                if (text.isEmpty()) {
                    throw new IllegalStateException("响应缺少 text 字段");
                }
                lastGoodText = text;
                lastGoodStamp = nowStamp();
                boolean stale = payload.optBoolean("stale", false);
                String outgoing = lastGoodText + "\n" + lastGoodStamp
                        + (stale ? Bridge.TEXT_CACHE_SUFFIX : "");
                pushToSystemUi(payload, outgoing, stale ? Bridge.STATE_STALE : Bridge.STATE_OK);
                status = "已推送：" + outgoing;
            } catch (Exception error) {
                String outgoing = lastGoodText.isEmpty()
                        ? Bridge.TEXT_NO_CONNECTION
                        : lastGoodText + "\n" + lastGoodStamp + Bridge.TEXT_OFFLINE_SUFFIX;
                pushTextOnly(outgoing, Bridge.STATE_OFFLINE);
                status = "取数失败：" + error.getMessage();
            }
            AppConfig.setStatus(this, status);
            updateNotification(status);
            appConfigRefreshPause();
        }
    }

    private static String nowStamp() {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
    }

    private void appConfigRefreshPause() {
        int pollSec = AppConfig.pollSec(this);
        for (int i = 0; i < pollSec && running; i++) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException interrupt) {
                return;
            }
        }
    }

    private void pushToSystemUi(JSONObject payload, String text, String state) {
        Intent intent = new Intent(Bridge.ACTION);
        intent.setPackage(Bridge.TARGET_PACKAGE);
        intent.putExtra(Bridge.EXTRA_TEXT, text);
        intent.putExtra(Bridge.EXTRA_STATE, state);
        putConfigExtras(intent);
        intent.putExtra(Bridge.EXTRA_TOKENS, payload.optLong("total_tokens", 0));
        pushCreditsToSystemUi(intent, payload.optJSONObject("credits"));
        intent.putExtra(Bridge.EXTRA_COST, String.valueOf(payload.opt("cost_cny")));
        intent.putExtra(Bridge.EXTRA_SOURCE, payload.optString("source", ""));
        intent.putExtra(Bridge.EXTRA_UPDATED_AT, payload.optString("updated_at", ""));
        sendBroadcast(intent);
    }

    /** 取数失败时没有 payload，只推文本与状态，让 AOD 能显示“离线”而不是一直停在旧数据上。 */
    private void pushTextOnly(String text, String state) {
        Intent intent = new Intent(Bridge.ACTION);
        intent.setPackage(Bridge.TARGET_PACKAGE);
        intent.putExtra(Bridge.EXTRA_TEXT, text);
        intent.putExtra(Bridge.EXTRA_STATE, state);
        putConfigExtras(intent);
        sendBroadcast(intent);
    }

    /** 每次广播都带上显示配置，改设置后最多一个轮询周期即可生效。 */
    private void putConfigExtras(Intent intent) {
        intent.putExtra(Bridge.EXTRA_SHIFT, AppConfig.shift(this));
        intent.putExtra(Bridge.EXTRA_DURATION_SEC, AppConfig.durationSec(this));
        intent.putExtra(Bridge.EXTRA_KEEP_AOD, AppConfig.keepAod(this));
        intent.putExtra(Bridge.EXTRA_HIDE_FP, AppConfig.hideFp(this));
        intent.putExtra(Bridge.EXTRA_WINDOW_FROM, AppConfig.windowFrom(this));
        intent.putExtra(Bridge.EXTRA_WINDOW_TO, AppConfig.windowTo(this));
    }

    /** 积分合计（国内/国际）随广播下发；AOD 最下方一行左右分显。 */
    private void pushCreditsToSystemUi(Intent intent, org.json.JSONObject credits) {
        if (credits == null) {
            return;
        }
        intent.putExtra(Bridge.EXTRA_CREDITS_CN,
                String.valueOf(credits.opt("cn")));
        intent.putExtra(Bridge.EXTRA_CREDITS_INTL,
                String.valueOf(credits.opt("intl")));
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "AOD Bridge", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("息屏用量取数服务");
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("AOD Bridge")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
