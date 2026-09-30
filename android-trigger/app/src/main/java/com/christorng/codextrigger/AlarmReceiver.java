package com.christorng.codextrigger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                Scheduler.runCycle(context.getApplicationContext(), false);
            } finally {
                pending.finish();
            }
        }, "codex-alarm").start();
    }
}
