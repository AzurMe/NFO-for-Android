package org.kodiscraper.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

final class UiIcon extends Drawable {
    enum Kind { MENU, CLOSE, LIBRARY, FILM, SUBTITLES, RECOMMEND, LOG, APPEARANCE, INFO }

    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private final Kind kind;
    private final int size;

    UiIcon(Kind kind, int size, int color) {
        this.kind = kind;
        this.size = size;
        ink.setColor(color);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(1.65f);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public void draw(Canvas canvas) {
        int restore = canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        switch (kind) {
            case MENU:
                canvas.drawLine(4, 7, 20, 7, ink);
                canvas.drawLine(4, 12, 17, 12, ink);
                canvas.drawLine(4, 17, 13, 17, ink);
                break;
            case CLOSE:
                canvas.drawLine(6, 6, 18, 18, ink);
                canvas.drawLine(18, 6, 6, 18, ink);
                break;
            case LIBRARY:
                canvas.drawRoundRect(4, 4, 10, 10, 1.35f, 1.35f, ink);
                canvas.drawRoundRect(14, 4, 20, 10, 1.35f, 1.35f, ink);
                canvas.drawRoundRect(4, 14, 10, 20, 1.35f, 1.35f, ink);
                canvas.drawRoundRect(14, 14, 20, 20, 1.35f, 1.35f, ink);
                break;
            case FILM:
                canvas.drawRoundRect(4, 5, 20, 19, 2, 2, ink);
                canvas.drawLine(8, 5, 8, 19, ink);
                canvas.drawLine(16, 5, 16, 19, ink);
                for (int height = 10; height < 16; height += 4) {
                    canvas.drawLine(4, height, 8, height, ink);
                    canvas.drawLine(16, height, 20, height, ink);
                }
                break;
            case SUBTITLES:
                canvas.drawRoundRect(3.5f, 5, 20.5f, 19, 2.5f, 2.5f, ink);
                canvas.drawLine(7, 11, 12, 11, ink);
                canvas.drawLine(15, 11, 17, 11, ink);
                canvas.drawLine(7, 14.5f, 9, 14.5f, ink);
                canvas.drawLine(12, 14.5f, 17, 14.5f, ink);
                break;
            case RECOMMEND:
                outline.rewind();
                outline.moveTo(12, 3);
                outline.lineTo(14.5f, 9.5f);
                outline.lineTo(21, 12);
                outline.lineTo(14.5f, 14.5f);
                outline.lineTo(12, 21);
                outline.lineTo(9.5f, 14.5f);
                outline.lineTo(3, 12);
                outline.lineTo(9.5f, 9.5f);
                outline.close();
                canvas.drawPath(outline, ink);
                break;
            case LOG:
                canvas.drawRoundRect(5, 3.5f, 19, 20.5f, 2.4f, 2.4f, ink);
                canvas.drawLine(8.5f, 8, 15.5f, 8, ink);
                canvas.drawLine(8.5f, 12, 15.5f, 12, ink);
                canvas.drawLine(8.5f, 16, 13, 16, ink);
                break;
            case APPEARANCE:
                canvas.drawCircle(12, 12, 4, ink);
                for (int angle = 0; angle < 360; angle += 45) {
                    canvas.save();
                    canvas.rotate(angle, 12, 12);
                    canvas.drawLine(12, 3, 12, 5, ink);
                    canvas.restore();
                }
                break;
            case INFO:
                canvas.drawCircle(12, 12, 8.5f, ink);
                canvas.drawLine(12, 11.5f, 12, 16, ink);
                canvas.drawPoint(12, 8, ink);
                break;
        }
        canvas.restoreToCount(restore);
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setTint(int color) { ink.setColor(color); invalidateSelf(); }
    @Override public void setAlpha(int alpha) { ink.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { ink.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
