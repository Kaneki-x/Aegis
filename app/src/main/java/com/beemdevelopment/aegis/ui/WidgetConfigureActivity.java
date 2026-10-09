package com.beemdevelopment.aegis.ui;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.helpers.ViewHelper;
import com.beemdevelopment.aegis.ui.glide.GlideHelper;
import com.beemdevelopment.aegis.vault.VaultEntry;
import com.beemdevelopment.aegis.widget.AegisWidgetProvider;
import com.bumptech.glide.Glide;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Lets the user pick which entries a home screen widget shows: either the favorites
 * (kept in sync automatically) or an explicit selection.
 */
public class WidgetConfigureActivity extends AegisActivity {
    private int _widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private boolean _isAuthenticating;

    private MaterialSwitch _favoritesSwitch;
    private View _selectHint;
    private RecyclerView _list;
    private EntryAdapter _adapter;

    private final ActivityResultLauncher<Intent> _authResultLauncher =
            registerForActivityResult(new StartActivityForResult(), result -> {
                _isAuthenticating = false;
                if (result.getResultCode() != RESULT_OK) {
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);

        Intent intent = getIntent();
        if (intent.getExtras() != null) {
            _widgetId = intent.getExtras().getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (_widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        setContentView(R.layout.activity_widget_configure);
        setSupportActionBar(findViewById(R.id.toolbar));
        ViewHelper.setupAppBarInsets(findViewById(R.id.app_bar_layout));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        _favoritesSwitch = findViewById(R.id.switch_favorites);
        _selectHint = findViewById(R.id.select_hint);
        _list = findViewById(R.id.list_entries);
        _adapter = new EntryAdapter(new LinkedHashSet<>(_prefs.getWidgetEntries(_widgetId)));
        _list.setLayoutManager(new LinearLayoutManager(this));
        _list.setAdapter(_adapter);

        _favoritesSwitch.setChecked(_prefs.isWidgetFavoritesMode(_widgetId));
        findViewById(R.id.favorites_row).setOnClickListener(v -> {
            _favoritesSwitch.setChecked(!_favoritesSwitch.isChecked());
            updateMode();
        });
        updateMode();

        ExtendedFloatingActionButton fab = findViewById(R.id.fab_save);
        fab.setOnClickListener(v -> save());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (_widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            return;
        }

        if (_vaultManager.loadIfUnencrypted()) {
            loadEntries();
        } else if (!_isAuthenticating) {
            _isAuthenticating = true;
            Intent intent = new Intent(this, AuthActivity.class);
            intent.putExtra("inhibitBioPrompt", false);
            _authResultLauncher.launch(intent);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void updateMode() {
        boolean favorites = _favoritesSwitch.isChecked();
        _selectHint.setVisibility(favorites ? View.GONE : View.VISIBLE);
        _list.setVisibility(favorites ? View.GONE : View.VISIBLE);
    }

    private void loadEntries() {
        List<VaultEntry> entries = new ArrayList<>(_vaultManager.getVault().getEntries());
        Collections.sort(entries, Comparator
                .comparing((VaultEntry e) -> !e.isFavorite())
                .thenComparing(WidgetConfigureActivity::getTitle, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(VaultEntry::getName, String.CASE_INSENSITIVE_ORDER));
        _adapter.setEntries(entries);
    }

    private static String getTitle(VaultEntry entry) {
        String issuer = entry.getIssuer().trim();
        return issuer.isEmpty() ? entry.getName().trim() : issuer;
    }

    private void save() {
        _prefs.setWidgetFavoritesMode(_widgetId, _favoritesSwitch.isChecked());
        _prefs.setWidgetEntries(_widgetId, _adapter.getSelectedInOrder());
        AegisWidgetProvider.update(this, _widgetId);

        Intent result = new Intent();
        result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, _widgetId);
        setResult(RESULT_OK, result);
        finish();
    }

    private class EntryAdapter extends RecyclerView.Adapter<EntryAdapter.Holder> {
        private final Set<UUID> _selected;
        private List<VaultEntry> _entries = new ArrayList<>();

        EntryAdapter(Set<UUID> selected) {
            _selected = selected;
        }

        void setEntries(List<VaultEntry> entries) {
            _entries = entries;
            notifyDataSetChanged();
        }

        List<UUID> getSelectedInOrder() {
            List<UUID> uuids = new ArrayList<>();
            for (VaultEntry entry : _entries) {
                if (_selected.contains(entry.getUUID())) {
                    uuids.add(entry.getUUID());
                }
            }
            return uuids;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.card_widget_entry, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(_entries.get(position));
        }

        @Override
        public int getItemCount() {
            return _entries.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            private final ImageView _icon;
            private final TextView _issuer;
            private final TextView _name;
            private final MaterialCheckBox _checkBox;
            private VaultEntry _entry;

            Holder(View view) {
                super(view);
                _icon = view.findViewById(R.id.icon);
                _issuer = view.findViewById(R.id.issuer);
                _name = view.findViewById(R.id.name);
                _checkBox = view.findViewById(R.id.checkbox);
                view.setOnClickListener(v -> {
                    if (_entry == null) {
                        return;
                    }
                    if (!_selected.remove(_entry.getUUID())) {
                        _selected.add(_entry.getUUID());
                    }
                    _checkBox.setChecked(_selected.contains(_entry.getUUID()));
                });
            }

            void bind(VaultEntry entry) {
                _entry = entry;
                String issuer = entry.getIssuer();
                String name = entry.getName();
                if (issuer.isEmpty()) {
                    issuer = name;
                    name = "";
                }
                _issuer.setText(issuer);
                _name.setText(name);
                _name.setVisibility(name.isEmpty() ? View.GONE : View.VISIBLE);
                _checkBox.setChecked(_selected.contains(entry.getUUID()));
                GlideHelper.loadEntryIcon(Glide.with(WidgetConfigureActivity.this), entry, _icon);
            }
        }
    }
}
