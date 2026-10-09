package com.beemdevelopment.aegis.helpers;

import android.content.Context;
import android.content.res.Configuration;

import androidx.appcompat.view.ContextThemeWrapper;

import androidx.appcompat.app.AppCompatActivity;

import com.beemdevelopment.aegis.Preferences;
import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.Theme;
import com.beemdevelopment.aegis.ThemeMap;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;

import java.util.Locale;
import java.util.Map;

public class ThemeHelper {
    private final AppCompatActivity _activity;
    private final Preferences _prefs;

    public ThemeHelper(AppCompatActivity activity, Preferences prefs) {
        _activity = activity;
        _prefs = prefs;
    }

    /**
     * Sets the theme of the activity. The actual style that is set is picked from the
     * given map, based on the theme configured by the user.
     */
    public void setTheme(Map<Theme, Integer> themeMap) {
        int theme = themeMap.get(getConfiguredTheme());
        _activity.setTheme(theme);

        if (_prefs.isDynamicColorsEnabled()) {
            DynamicColorsOptions.Builder optsBuilder = new DynamicColorsOptions.Builder();
            if (getConfiguredTheme().equals(Theme.AMOLED)) {
                optsBuilder.setThemeOverlay(R.style.ThemeOverlay_Aegis_Dynamic_Amoled);
            } else if (getConfiguredTheme().equals(Theme.DARK)) {
                optsBuilder.setThemeOverlay(R.style.ThemeOverlay_Aegis_Dynamic_Dark);
            }

            DynamicColors.applyToActivityIfAvailable(_activity, optsBuilder.build());
        }
    }

    public Theme getConfiguredTheme() {
        return getConfiguredTheme(_activity, _prefs);
    }

    /**
     * Resolves the theme configured by the user to a concrete theme, taking the current
     * night mode of the given context into account.
     */
    public static Theme getConfiguredTheme(Context context, Preferences prefs) {
        Theme theme = prefs.getCurrentTheme();

        if (theme == Theme.SYSTEM || theme == Theme.SYSTEM_AMOLED) {
            int currentNightMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            if (currentNightMode == Configuration.UI_MODE_NIGHT_YES) {
                theme = theme == Theme.SYSTEM_AMOLED ? Theme.AMOLED : Theme.DARK;
            } else {
                theme = Theme.LIGHT;
            }
        }

        return theme;
    }

    /**
     * Creates a context that carries the user's theme, dynamic colors and locale. Used by
     * components that don't have an Activity, such as the input method.
     */
    public static Context createThemedContext(Context base, Preferences prefs) {
        Locale locale = prefs.getLocale();
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        Context context = base.createConfigurationContext(config);

        Theme theme = getConfiguredTheme(context, prefs);
        context = new ContextThemeWrapper(context, ThemeMap.DEFAULT.get(theme));

        if (prefs.isDynamicColorsEnabled()) {
            if (theme == Theme.AMOLED) {
                context = DynamicColors.wrapContextIfAvailable(context, R.style.ThemeOverlay_Aegis_Dynamic_Amoled);
            } else if (theme == Theme.DARK) {
                context = DynamicColors.wrapContextIfAvailable(context, R.style.ThemeOverlay_Aegis_Dynamic_Dark);
            } else {
                context = DynamicColors.wrapContextIfAvailable(context);
            }
        }

        return context;
    }

    /**
     * Returns a string that changes whenever createThemedContext would produce a
     * differently themed context, so that callers know when to re-inflate their views.
     */
    public static String getThemeKey(Context context, Preferences prefs) {
        return getConfiguredTheme(context, prefs).name()
                + "/" + prefs.isDynamicColorsEnabled()
                + "/" + prefs.getLocale();
    }
}
