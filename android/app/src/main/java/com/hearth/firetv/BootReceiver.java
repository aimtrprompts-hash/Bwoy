package com.hearth.firetv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Opens Hearth once the Fire TV has started, when that is switched on in Hearth's settings. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) return;
        if (!HomeState.bootEnabled(context)) return;
        Intent open = new Intent(context, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(open);
        } catch (RuntimeException ignored) {
            // the Home watcher (if on) opens Hearth when Amazon's home appears anyway
        }
    }
}
