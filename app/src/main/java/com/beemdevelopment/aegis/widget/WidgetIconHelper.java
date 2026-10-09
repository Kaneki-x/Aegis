package com.beemdevelopment.aegis.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import androidx.annotation.Nullable;

import com.beemdevelopment.aegis.encoding.Hex;
import com.beemdevelopment.aegis.helpers.TextDrawableHelper;
import com.beemdevelopment.aegis.icons.IconType;
import com.beemdevelopment.aegis.vault.VaultEntry;
import com.beemdevelopment.aegis.vault.VaultEntryIcon;
import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;

import java.io.ByteArrayInputStream;

/**
 * Renders entry icons to small circular bitmaps for use in RemoteViews. Bitmaps cross the
 * Binder boundary on every widget update, so they're kept small and cached.
 */
public class WidgetIconHelper {
    private static final int ICON_DP = 32;
    private static final LruCache<String, Bitmap> _cache = new LruCache<>(64);

    private WidgetIconHelper() {

    }

    @Nullable
    public static Bitmap getIcon(Context context, VaultEntry entry) {
        int size = Math.round(ICON_DP * context.getResources().getDisplayMetrics().density);
        String key;
        if (entry.hasIcon()) {
            key = Hex.encode(entry.getIcon().getHash()) + "/" + size;
        } else {
            key = "text:" + entry.getIssuer() + "/" + entry.getName() + "/" + size;
        }

        synchronized (_cache) {
            Bitmap cached = _cache.get(key);
            if (cached != null) {
                return cached;
            }
        }

        Bitmap bitmap = entry.hasIcon() ? render(entry.getIcon(), size) : null;
        if (bitmap == null) {
            Drawable drawable = TextDrawableHelper.generate(entry.getIssuer(), entry.getName(), size, size);
            if (drawable == null) {
                return null;
            }
            bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
        }

        synchronized (_cache) {
            _cache.put(key, bitmap);
        }
        return bitmap;
    }

    @Nullable
    private static Bitmap render(VaultEntryIcon icon, int size) {
        Bitmap source;
        if (icon.getType() == IconType.SVG) {
            source = renderSvg(icon.getBytes(), size);
        } else {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(icon.getBytes(), 0, icon.getBytes().length, opts);
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = calculateSampleSize(opts.outWidth, opts.outHeight, size);
            source = BitmapFactory.decodeByteArray(icon.getBytes(), 0, icon.getBytes().length, opts);
        }

        if (source == null) {
            return null;
        }

        return toCircle(source, size);
    }

    @Nullable
    private static Bitmap renderSvg(byte[] bytes, int size) {
        try {
            SVG svg = SVG.getFromInputStream(new ByteArrayInputStream(bytes));
            svg.setDocumentWidth(size);
            svg.setDocumentHeight(size);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            svg.renderToCanvas(new Canvas(bitmap));
            return bitmap;
        } catch (SVGParseException | IllegalArgumentException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static int calculateSampleSize(int width, int height, int target) {
        int sampleSize = 1;
        while (width / (sampleSize * 2) >= target && height / (sampleSize * 2) >= target) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    private static Bitmap toCircle(Bitmap source, int size) {
        Bitmap scaled = Bitmap.createScaledBitmap(source, size, size, true);
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float radius = size / 2f;
        canvas.drawCircle(radius, radius, radius, paint);
        return output;
    }
}
