package io.aodbridge.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机自启（仅当用户在界面里启用过轮询）。部分 ROM 需用户手动允许自启动。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (!AppConfig.enabled(context)) {
            return;
        }
        try {
            Intent service = new Intent(context, PollService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Throwable ignored) {
        }
    }
}
