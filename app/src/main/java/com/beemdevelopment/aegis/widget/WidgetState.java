package com.beemdevelopment.aegis.widget;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory bookkeeping of which widget rows currently show their code. Living only in
 * memory is intentional: if the process dies, every code is hidden again.
 */
public class WidgetState {
    private static final Map<Integer, Map<UUID, Long>> _revealed = new HashMap<>();
    private static final Map<Integer, Runnable> _refreshRunnables = new HashMap<>();
    private static final Handler _handler = new Handler(Looper.getMainLooper());

    private WidgetState() {

    }

    public static synchronized void reveal(int widgetId, UUID uuid, long untilMillis) {
        Map<UUID, Long> map = _revealed.get(widgetId);
        if (map == null) {
            map = new HashMap<>();
            _revealed.put(widgetId, map);
        }
        map.put(uuid, untilMillis);
    }

    public static synchronized boolean isRevealed(int widgetId, UUID uuid) {
        Map<UUID, Long> map = _revealed.get(widgetId);
        if (map == null) {
            return false;
        }

        Long until = map.get(uuid);
        return until != null && System.currentTimeMillis() < until;
    }

    /**
     * Removes expired reveals and reports whether any reveal is still active for the widget.
     */
    public static synchronized boolean purgeExpired(int widgetId) {
        Map<UUID, Long> map = _revealed.get(widgetId);
        if (map == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> iter = map.entrySet().iterator();
        while (iter.hasNext()) {
            if (now >= iter.next().getValue()) {
                iter.remove();
            }
        }

        if (map.isEmpty()) {
            _revealed.remove(widgetId);
            return false;
        }

        return true;
    }

    @Nullable
    public static synchronized Long getEarliestExpiry(int widgetId) {
        Map<UUID, Long> map = _revealed.get(widgetId);
        if (map == null || map.isEmpty()) {
            return null;
        }

        long min = Long.MAX_VALUE;
        for (long until : map.values()) {
            min = Math.min(min, until);
        }
        return min;
    }

    public static synchronized void hideAll(int widgetId) {
        _revealed.remove(widgetId);
        cancelRefresh(widgetId);
    }

    public static synchronized void hideAll() {
        _revealed.clear();
        for (Runnable runnable : _refreshRunnables.values()) {
            _handler.removeCallbacks(runnable);
        }
        _refreshRunnables.clear();
    }

    public static synchronized void scheduleRefresh(int widgetId, long delayMillis, Runnable runnable) {
        cancelRefresh(widgetId);
        _refreshRunnables.put(widgetId, runnable);
        _handler.postDelayed(runnable, Math.max(0, delayMillis));
    }

    public static synchronized void cancelRefresh(int widgetId) {
        Runnable runnable = _refreshRunnables.remove(widgetId);
        if (runnable != null) {
            _handler.removeCallbacks(runnable);
        }
    }
}
