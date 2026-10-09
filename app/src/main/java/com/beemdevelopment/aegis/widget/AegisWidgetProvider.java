package com.beemdevelopment.aegis.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.beemdevelopment.aegis.BuildConfig;
import com.beemdevelopment.aegis.Preferences;
import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.otp.TotpInfo;
import com.beemdevelopment.aegis.ui.AuthActivity;
import com.beemdevelopment.aegis.ui.MainActivity;
import com.beemdevelopment.aegis.ui.WidgetConfigureActivity;
import com.beemdevelopment.aegis.vault.VaultEntry;
import com.beemdevelopment.aegis.vault.VaultManager;
import com.beemdevelopment.aegis.vault.VaultRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import dagger.hilt.android.EntryPointAccessors;

/**
 * Home screen widget that lists the codes of selected entries. Codes are hidden until a
 * row is tapped and never rendered while the vault is locked.
 */
public class AegisWidgetProvider extends AppWidgetProvider {
    public static final String EXTRA_UUID = "uuid";
    static final String ACTION_ROW_CLICK = BuildConfig.APPLICATION_ID + ".WIDGET_ROW_CLICK";
    static final String ACTION_HIDE = BuildConfig.APPLICATION_ID + ".WIDGET_HIDE";

    private static final long HIDE_BACKSTOP_SLACK_MILLIS = 3000;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            render(context, appWidgetManager, id);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, int appWidgetId, Bundle newOptions) {
        render(context, appWidgetManager, appWidgetId);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        Preferences prefs = getEntryPoint(context).getPreferences();
        for (int id : appWidgetIds) {
            prefs.clearWidget(id);
            WidgetState.hideAll(id);
        }
    }

    @Override
    public void onDisabled(Context context) {
        WidgetState.hideAll();
    }

    /**
     * Called by VaultManager whenever the vault gets loaded or locked.
     */
    public static void onVaultStateChanged(Context context) {
        VaultManager vaultManager = getEntryPoint(context).getVaultManager();
        if (!vaultManager.isVaultLoaded()) {
            WidgetState.hideAll();
        }
        updateAll(context);
    }

    public static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null) {
            return;
        }

        int[] ids = manager.getAppWidgetIds(new ComponentName(context, AegisWidgetProvider.class));
        for (int id : ids) {
            render(context, manager, id);
        }
    }

    public static void update(Context context, int widgetId) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager != null) {
            render(context, manager, widgetId);
        }
    }

    /**
     * Reveals the code of the given entry in the given widget for a while. If the vault is
     * locked, the user is sent through AuthActivity first and this method is called again
     * (through requestReveal) once the vault is unlocked.
     */
    public static void reveal(Context context, int widgetId, @Nullable String uuidString) {
        WidgetEntryPoint entryPoint = getEntryPoint(context);
        VaultManager vaultManager = entryPoint.getVaultManager();
        Preferences prefs = entryPoint.getPreferences();

        if (vaultManager.isVaultInitNeeded()) {
            update(context, widgetId);
            return;
        }

        if (!vaultManager.loadIfUnencrypted()) {
            Intent intent = AuthActivity.createExternalIntent(context);
            intent.putExtra(AuthActivity.EXTRA_WIDGET_ID, widgetId);
            intent.putExtra(AuthActivity.EXTRA_WIDGET_REVEAL_UUID, uuidString);
            context.startActivity(intent);
            return;
        }

        UUID uuid = parseUuid(uuidString);
        if (uuid != null && vaultManager.getVault().hasEntryByUUID(uuid)) {
            long revealMillis = prefs.getTapToRevealTime() * 1000L;
            long until = System.currentTimeMillis() + revealMillis;
            WidgetState.reveal(widgetId, uuid, until);
            vaultManager.holdForWidget(revealMillis + 1000);
            scheduleHideBackstop(context, widgetId, until);
        }

        update(context, widgetId);
        scheduleRefresh(context, widgetId);
    }

    public static void requestReveal(Context context, int widgetId, @Nullable String uuidString) {
        reveal(context, widgetId, uuidString);
    }

    static void render(Context context, AppWidgetManager manager, int widgetId) {
        WidgetEntryPoint entryPoint = getEntryPoint(context);
        VaultManager vaultManager = entryPoint.getVaultManager();
        Preferences prefs = entryPoint.getPreferences();

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_aegis);
        views.setOnClickPendingIntent(R.id.widget_header, createOpenAppIntent(context, widgetId));

        if (vaultManager.isVaultInitNeeded()) {
            showMessage(context, views, R.string.widget_setup_required, createOpenAppIntent(context, widgetId), false);
        } else if (!vaultManager.loadIfUnencrypted()) {
            Intent intent = AuthActivity.createExternalIntent(context);
            intent.putExtra(AuthActivity.EXTRA_WIDGET_ID, widgetId);
            PendingIntent pendingIntent = PendingIntent.getActivity(context, widgetId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            showMessage(context, views, R.string.widget_locked, pendingIntent, true);
        } else if (resolveEntries(vaultManager.getVault(), prefs, widgetId).isEmpty()) {
            Intent intent = new Intent(context, WidgetConfigureActivity.class);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pendingIntent = PendingIntent.getActivity(context, widgetId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            showMessage(context, views, R.string.widget_no_entries, pendingIntent, false);
        } else {
            views.setViewVisibility(R.id.widget_message_box, View.GONE);
            views.setViewVisibility(R.id.widget_lock_icon, View.GONE);
            views.setViewVisibility(R.id.widget_list, View.VISIBLE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // RemoteCollectionItems is the supported collection API since Android 12
                // and doesn't need a service binding
                RemoteViews.RemoteCollectionItems.Builder builder = new RemoteViews.RemoteCollectionItems.Builder();
                Preferences.CodeGrouping grouping = prefs.getCodeGroupSize();
                for (VaultEntry entry : resolveEntries(vaultManager.getVault(), prefs, widgetId)) {
                    builder.addItem(entry.getUUID().getMostSignificantBits() ^ entry.getUUID().getLeastSignificantBits(),
                            WidgetRowFactory.build(context, widgetId, entry, true, grouping));
                }
                builder.setViewTypeCount(1).setHasStableIds(true);
                views.setRemoteAdapter(R.id.widget_list, builder.build());
            } else {
                Intent serviceIntent = new Intent(context, AegisWidgetService.class);
                serviceIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
                serviceIntent.setData(Uri.parse(serviceIntent.toUri(Intent.URI_INTENT_SCHEME)));
                views.setRemoteAdapter(R.id.widget_list, serviceIntent);
            }

            Intent clickIntent = new Intent(context, WidgetActionReceiver.class);
            clickIntent.setAction(ACTION_ROW_CLICK);
            clickIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            views.setPendingIntentTemplate(R.id.widget_list, PendingIntent.getBroadcast(context, widgetId, clickIntent, flags));
        }

        manager.updateAppWidget(widgetId, views);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list);
        }
    }

    private static void showMessage(Context context, RemoteViews views, int textRes, PendingIntent onClick, boolean locked) {
        views.setViewVisibility(R.id.widget_list, View.GONE);
        views.setViewVisibility(R.id.widget_message_box, View.VISIBLE);
        views.setViewVisibility(R.id.widget_lock_icon, locked ? View.VISIBLE : View.GONE);
        views.setTextViewText(R.id.widget_message, context.getString(textRes));
        views.setOnClickPendingIntent(R.id.widget_message_box, onClick);
    }

    private static PendingIntent createOpenAppIntent(Context context, int requestCode) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, 10_000 + requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Returns the entries the widget should show, in display order.
     */
    @NonNull
    static List<VaultEntry> resolveEntries(VaultRepository vault, Preferences prefs, int widgetId) {
        List<VaultEntry> entries = new ArrayList<>();
        if (prefs.isWidgetFavoritesMode(widgetId)) {
            for (VaultEntry entry : vault.getEntries()) {
                if (entry.isFavorite()) {
                    entries.add(entry);
                }
            }
            Collections.sort(entries, Comparator
                    .comparing(AegisWidgetProvider::getTitle, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(VaultEntry::getName, String.CASE_INSENSITIVE_ORDER));
        } else {
            for (UUID uuid : prefs.getWidgetEntries(widgetId)) {
                if (vault.hasEntryByUUID(uuid)) {
                    entries.add(vault.getEntryByUUID(uuid));
                }
            }
        }
        return entries;
    }

    static String getTitle(VaultEntry entry) {
        String issuer = entry.getIssuer().trim();
        return issuer.isEmpty() ? entry.getName().trim() : issuer;
    }

    /**
     * Re-renders the widget when the next revealed code rotates or the reveal expires.
     */
    static void scheduleRefresh(Context context, int widgetId) {
        WidgetState.cancelRefresh(widgetId);
        if (!WidgetState.purgeExpired(widgetId)) {
            return;
        }

        WidgetEntryPoint entryPoint = getEntryPoint(context);
        VaultManager vaultManager = entryPoint.getVaultManager();
        if (!vaultManager.isVaultLoaded()) {
            return;
        }

        long now = System.currentTimeMillis();
        Long expiry = WidgetState.getEarliestExpiry(widgetId);
        long next = expiry != null ? expiry : now + 1000;
        for (VaultEntry entry : resolveEntries(vaultManager.getVault(), entryPoint.getPreferences(), widgetId)) {
            if (entry.getInfo() instanceof TotpInfo && WidgetState.isRevealed(widgetId, entry.getUUID())) {
                next = Math.min(next, now + ((TotpInfo) entry.getInfo()).getMillisTillNextRotation());
            }
        }

        Context appContext = context.getApplicationContext();
        WidgetState.scheduleRefresh(widgetId, next - now + 50, () -> {
            WidgetState.purgeExpired(widgetId);
            update(appContext, widgetId);
            scheduleRefresh(appContext, widgetId);
        });
    }

    /**
     * Makes sure the code gets hidden even if the in-process timer never fires.
     */
    private static void scheduleHideBackstop(Context context, int widgetId, long untilMillis) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }

        Intent intent = new Intent(context, WidgetActionReceiver.class);
        intent.setAction(ACTION_HIDE);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarmManager.set(AlarmManager.RTC_WAKEUP, untilMillis + HIDE_BACKSTOP_SLACK_MILLIS, pendingIntent);
    }

    @Nullable
    private static UUID parseUuid(@Nullable String uuid) {
        if (uuid == null) {
            return null;
        }

        try {
            return UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static WidgetEntryPoint getEntryPoint(Context context) {
        return EntryPointAccessors.fromApplication(context.getApplicationContext(), WidgetEntryPoint.class);
    }
}
