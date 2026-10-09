package com.beemdevelopment.aegis.widget;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;


import com.beemdevelopment.aegis.Preferences;
import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.vault.VaultEntry;
import com.beemdevelopment.aegis.vault.VaultManager;

import java.util.ArrayList;
import java.util.List;

public class AegisWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        return new Factory(getApplicationContext(), widgetId);
    }

    private static class Factory implements RemoteViewsFactory {
        private final Context _context;
        private final int _widgetId;
        private final VaultManager _vaultManager;
        private final Preferences _prefs;

        private List<VaultEntry> _entries = new ArrayList<>();
        private Preferences.CodeGrouping _grouping = Preferences.CodeGrouping.GROUPING_THREES;

        Factory(Context context, int widgetId) {
            _context = context;
            _widgetId = widgetId;

            WidgetEntryPoint entryPoint = AegisWidgetProvider.getEntryPoint(context);
            _vaultManager = entryPoint.getVaultManager();
            _prefs = entryPoint.getPreferences();
        }

        @Override
        public void onCreate() {

        }

        @Override
        public void onDataSetChanged() {
            _grouping = _prefs.getCodeGroupSize();
            if (_vaultManager.isVaultLoaded()) {
                _entries = AegisWidgetProvider.resolveEntries(_vaultManager.getVault(), _prefs, _widgetId);
            } else {
                _entries = new ArrayList<>();
            }
        }

        @Override
        public void onDestroy() {
            _entries = new ArrayList<>();
        }

        @Override
        public int getCount() {
            return _entries.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position >= _entries.size()) {
                return new RemoteViews(_context.getPackageName(), R.layout.widget_entry_item);
            }

            return WidgetRowFactory.build(_context, _widgetId, _entries.get(position), _vaultManager.isVaultLoaded(), _grouping);
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position < _entries.size() ? _entries.get(position).getUUID().hashCode() : position;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }
    }
}
