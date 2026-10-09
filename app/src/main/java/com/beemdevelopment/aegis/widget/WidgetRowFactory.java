package com.beemdevelopment.aegis.widget;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;

import com.beemdevelopment.aegis.Preferences;
import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.helpers.CodeFormatHelper;
import com.beemdevelopment.aegis.otp.OtpInfo;
import com.beemdevelopment.aegis.otp.OtpInfoException;
import com.beemdevelopment.aegis.otp.TotpInfo;
import com.beemdevelopment.aegis.vault.VaultEntry;

/**
 * Builds the RemoteViews for a single widget row. Shared by the RemoteCollectionItems
 * path (API 31+) and the RemoteViewsService path (older versions).
 */
public class WidgetRowFactory {
    private WidgetRowFactory() {

    }

    public static RemoteViews build(Context context, int widgetId, VaultEntry entry, boolean vaultLoaded, Preferences.CodeGrouping grouping) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_entry_item);
        OtpInfo info = entry.getInfo();

        Bitmap icon = WidgetIconHelper.getIcon(context, entry);
        if (icon != null) {
            views.setImageViewBitmap(R.id.widget_icon, icon);
        }

        String issuer = entry.getIssuer();
        String name = entry.getName();
        if (issuer.isEmpty()) {
            issuer = name;
            name = "";
        }
        views.setTextViewText(R.id.widget_issuer, issuer);
        views.setTextViewText(R.id.widget_name, name);
        views.setViewVisibility(R.id.widget_name, name.isEmpty() ? View.GONE : View.VISIBLE);

        boolean revealed = vaultLoaded && WidgetState.isRevealed(widgetId, entry.getUUID());
        if (revealed) {
            String code;
            try {
                code = CodeFormatHelper.format(info.getOtp(), info, grouping);
            } catch (OtpInfoException e) {
                code = context.getString(R.string.error_all_caps);
            }
            views.setTextViewText(R.id.widget_code, code);
            views.setTextColor(R.id.widget_code, ContextCompat.getColor(context, R.color.widget_primary));
        } else {
            StringBuilder dots = new StringBuilder();
            for (int i = 0; i < info.getDigits(); i++) {
                dots.append(CodeFormatHelper.HIDDEN_CHAR);
            }
            views.setTextViewText(R.id.widget_code, CodeFormatHelper.format(dots.toString(), info, grouping));
            views.setTextColor(R.id.widget_code, ContextCompat.getColor(context, R.color.widget_code_hidden));
        }

        if (revealed && info instanceof TotpInfo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            long base = SystemClock.elapsedRealtime() + ((TotpInfo) info).getMillisTillNextRotation();
            views.setChronometerCountDown(R.id.widget_countdown, true);
            views.setChronometer(R.id.widget_countdown, base, null, true);
            views.setViewVisibility(R.id.widget_countdown, View.VISIBLE);
        } else {
            views.setViewVisibility(R.id.widget_countdown, View.GONE);
        }

        Intent fillIn = new Intent();
        fillIn.putExtra(AegisWidgetProvider.EXTRA_UUID, entry.getUUID().toString());
        views.setOnClickFillInIntent(R.id.widget_row, fillIn);
        return views;
    }
}
