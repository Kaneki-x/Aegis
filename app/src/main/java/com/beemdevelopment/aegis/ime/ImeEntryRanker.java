package com.beemdevelopment.aegis.ime;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.beemdevelopment.aegis.vault.VaultEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orders vault entries for the input method: entries that look like they belong to the
 * app that is currently asking for input come first, then favorites, then everything
 * else, each group sorted by how often they were used.
 */
public class ImeEntryRanker {
    private static final Set<String> IGNORED_TOKENS = new HashSet<>(Arrays.asList(
            "com", "org", "net", "io", "co", "de", "cn", "uk", "app", "apps", "android", "mobile",
            "client", "www", "the", "inc", "ltd", "llc", "official", "beta", "free", "pro", "lite",
            "for", "and"
    ));

    private static final String[] BROWSER_MARKERS = {
            "chrome", "chromium", "firefox", "browser", "brave", "opera", "vivaldi", "duckduckgo",
            "com.microsoft.emmx", "org.mozilla", "kiwibrowser", "com.sec.android.app.sbrowser"
    };

    private ImeEntryRanker() {

    }

    public static class Ranked {
        private final VaultEntry _entry;
        private final boolean _suggested;

        Ranked(VaultEntry entry, boolean suggested) {
            _entry = entry;
            _suggested = suggested;
        }

        public VaultEntry getEntry() {
            return _entry;
        }

        public boolean isSuggested() {
            return _suggested;
        }
    }

    /**
     * Derives search tokens from the package that currently has input focus. Returns an
     * empty set for browsers, because the package name says nothing about the website.
     */
    @NonNull
    public static Set<String> getCallerTokens(Context context, @Nullable String packageName) {
        Set<String> tokens = new HashSet<>();
        if (packageName == null || packageName.equals(context.getPackageName())) {
            return tokens;
        }

        String lowerPackage = packageName.toLowerCase(Locale.ROOT);
        for (String marker : BROWSER_MARKERS) {
            if (lowerPackage.contains(marker)) {
                return tokens;
            }
        }

        for (String part : lowerPackage.split("\\.")) {
            addToken(tokens, part);
        }

        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            if (label != null) {
                for (String part : label.toString().toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                    addToken(tokens, part);
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // the package may not be visible to us, the package name tokens will have to do
        }

        return tokens;
    }

    private static void addToken(Set<String> tokens, String token) {
        if (token.length() >= 3 && !IGNORED_TOKENS.contains(token)) {
            tokens.add(token);
        }
    }

    public static boolean matches(VaultEntry entry, Set<String> callerTokens) {
        if (callerTokens.isEmpty()) {
            return false;
        }

        String issuer = entry.getIssuer().toLowerCase(Locale.ROOT).trim();
        String name = entry.getName().toLowerCase(Locale.ROOT).trim();
        for (String token : callerTokens) {
            if (!issuer.isEmpty() && (issuer.contains(token) || (issuer.length() >= 3 && token.contains(issuer)))) {
                return true;
            }
            if (!name.isEmpty() && name.contains(token)) {
                return true;
            }
        }

        return false;
    }

    @NonNull
    public static List<Ranked> rank(Collection<VaultEntry> entries, Set<String> callerTokens, Map<UUID, Integer> usageCounts) {
        List<Ranked> ranked = new ArrayList<>(entries.size());
        for (VaultEntry entry : entries) {
            ranked.add(new Ranked(entry, matches(entry, callerTokens)));
        }

        Comparator<Ranked> comparator = Comparator
                .comparing((Ranked r) -> !r.isSuggested())
                .thenComparing(r -> !r.getEntry().isFavorite())
                .thenComparing(r -> -usageCountOf(usageCounts, r.getEntry()))
                .thenComparing(r -> getTitle(r.getEntry()), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(r -> r.getEntry().getName(), String.CASE_INSENSITIVE_ORDER);
        Collections.sort(ranked, comparator);
        return ranked;
    }

    /**
     * The text an entry is primarily known by: its issuer, or its name if there is no issuer.
     */
    public static String getTitle(VaultEntry entry) {
        String issuer = entry.getIssuer().trim();
        return issuer.isEmpty() ? entry.getName().trim() : issuer;
    }

    private static int usageCountOf(Map<UUID, Integer> usageCounts, VaultEntry entry) {
        Integer count = usageCounts.get(entry.getUUID());
        return count != null ? count : 0;
    }
}
