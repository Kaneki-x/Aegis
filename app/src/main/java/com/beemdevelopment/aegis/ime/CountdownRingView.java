package com.beemdevelopment.aegis.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.otp.TotpInfo;
import com.google.android.material.color.MaterialColors;

/**
 * A small self-animating ring that shows how much of the current TOTP period is left.
 * It drives its own redraws while visible and notifies a listener when the period rolls
 * over, so that the owner can refresh the code.
 */
public class CountdownRingView extends View {
    private static final long EXPIRING_MILLIS = 5000;

    private final Paint _trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint _arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF _rect = new RectF();

    private final int _colorNormal;
    private final int _colorExpiring;

    private final int _colorTrack;
    private boolean _hero;

    private long _periodMillis = TotpInfo.DEFAULT_PERIOD * 1000L;
    private long _lastSlot = -1;
    private Listener _listener;

    public CountdownRingView(Context context) {
        this(context, null);
    }

    public CountdownRingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        float stroke = getResources().getDisplayMetrics().density * 2.5f;
        _colorNormal = MaterialColors.getColor(this, R.attr.colorProgressbar);
        _colorExpiring = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError);
        _colorTrack = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHighest);

        _trackPaint.setStyle(Paint.Style.STROKE);
        _trackPaint.setStrokeWidth(stroke);
        _trackPaint.setColor(_colorTrack);

        _arcPaint.setStyle(Paint.Style.STROKE);
        _arcPaint.setStrokeWidth(stroke);
        _arcPaint.setStrokeCap(Paint.Cap.ROUND);
        _arcPaint.setColor(_colorNormal);
    }

    /**
     * On the brand-colored hero card the ring is drawn in white.
     */
    public void setHero(boolean hero) {
        _hero = hero;
        _trackPaint.setColor(hero ? 0x55FFFFFF : _colorTrack);
        invalidate();
    }

    public void setPeriod(int periodSeconds) {
        _periodMillis = Math.max(1, periodSeconds) * 1000L;
        _lastSlot = -1;
        invalidate();
    }

    public void setListener(@Nullable Listener listener) {
        _listener = listener;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float inset = _arcPaint.getStrokeWidth() / 2f + 1f;
        _rect.set(inset, inset, w - inset, h - inset);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        long now = System.currentTimeMillis();
        long slot = now / _periodMillis;
        long remaining = _periodMillis - (now % _periodMillis);
        float fraction = (float) remaining / _periodMillis;

        if (_lastSlot != -1 && slot != _lastSlot && _listener != null) {
            Listener listener = _listener;
            post(listener::onRotation);
        }
        _lastSlot = slot;

        int arcColor = remaining <= EXPIRING_MILLIS ? _colorExpiring : _colorNormal;
        _arcPaint.setColor(_hero ? (remaining <= EXPIRING_MILLIS ? 0xFFFFD6D6 : 0xFFFFFFFF) : arcColor);
        canvas.drawOval(_rect, _trackPaint);
        canvas.drawArc(_rect, -90, 360 * fraction, false, _arcPaint);

        if (isShown()) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        invalidate();
    }

    public interface Listener {
        void onRotation();
    }
}
