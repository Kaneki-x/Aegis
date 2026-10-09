package com.beemdevelopment.aegis.ui.fragments.preferences;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.preference.Preference;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.ui.dialogs.Dialogs;
import com.beemdevelopment.aegis.widget.AegisWidgetProvider;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class IntegrationsPreferencesFragment extends PreferencesFragment {
    private static final int[] GRACE_SECONDS = {0, 30, 60, 300, 900};

    private Preference _imeEnablePreference;
    private Preference _gracePreference;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.preferences_integrations);

        _imeEnablePreference = requirePreference("pref_ime_enable");
        _imeEnablePreference.setOnPreferenceClickListener(preference -> {
            Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            _vaultManager.setBlockAutoLock(true);
            startActivity(intent);
            return true;
        });

        Preference widgetPreference = requirePreference("pref_widget_add");
        widgetPreference.setOnPreferenceClickListener(preference -> {
            requestPinWidget();
            return true;
        });

        _gracePreference = requirePreference("pref_external_access_grace");
        _gracePreference.setSummary(getGraceSummary());
        _gracePreference.setOnPreferenceClickListener(preference -> {
            final String[] textItems = getResources().getStringArray(R.array.pref_external_grace_entries);
            int current = 0;
            for (int i = 0; i < GRACE_SECONDS.length; i++) {
                if (GRACE_SECONDS[i] == _prefs.getExternalAccessGraceSeconds()) {
                    current = i;
                }
            }

            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.pref_external_grace_prompt)
                    .setSingleChoiceItems(textItems, current, (dialog, which) -> {
                        _prefs.setExternalAccessGraceSeconds(GRACE_SECONDS[which]);
                        _gracePreference.setSummary(getGraceSummary());
                        dialog.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null);
            Dialogs.showSecureDialog(builder.create());
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        _imeEnablePreference.setSummary(isImeEnabled()
                ? R.string.pref_ime_enable_summary_enabled
                : R.string.pref_ime_enable_summary_disabled);
    }

    private boolean isImeEnabled() {
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) {
            return false;
        }

        for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
            if (info.getPackageName().equals(requireContext().getPackageName())) {
                return true;
            }
        }

        return false;
    }

    private void requestPinWidget() {
        AppWidgetManager manager = AppWidgetManager.getInstance(requireContext());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null && manager.isRequestPinAppWidgetSupported()) {
            _vaultManager.setBlockAutoLock(true);
            manager.requestPinAppWidget(new ComponentName(requireContext(), AegisWidgetProvider.class), null, null);
        } else {
            Toast.makeText(requireContext(), R.string.pref_widget_add_unsupported, Toast.LENGTH_LONG).show();
        }
    }

    private String getGraceSummary() {
        final String[] textItems = getResources().getStringArray(R.array.pref_external_grace_entries);
        int seconds = _prefs.getExternalAccessGraceSeconds();
        String current = textItems[0];
        for (int i = 0; i < GRACE_SECONDS.length; i++) {
            if (GRACE_SECONDS[i] == seconds) {
                current = textItems[i];
            }
        }

        return getString(R.string.pref_external_grace_summary, current);
    }
}
