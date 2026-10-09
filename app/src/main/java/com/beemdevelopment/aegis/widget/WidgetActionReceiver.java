package com.beemdevelopment.aegis.widget;

import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Handles taps on widget rows and the delayed "hide codes" alarm. Not exported, so only
 * our own PendingIntents can reach it.
 */
public class WidgetActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (action == null || widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            return;
        }

        switch (action) {
            case AegisWidgetProvider.ACTION_ROW_CLICK:
                AegisWidgetProvider.reveal(context, widgetId, intent.getStringExtra(AegisWidgetProvider.EXTRA_UUID));
                break;
            case AegisWidgetProvider.ACTION_HIDE:
                WidgetState.purgeExpired(widgetId);
                AegisWidgetProvider.update(context, widgetId);
                AegisWidgetProvider.scheduleRefresh(context, widgetId);
                break;
            default:
                break;
        }
    }
}
