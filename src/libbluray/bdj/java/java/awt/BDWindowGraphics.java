/*
 * This file is part of libbluray
 * Copyright (C) 2012  libbluray
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library. If not, see
 * <http://www.gnu.org/licenses/>.
 */

package java.awt;

import java.awt.image.ImageObserver;

import org.videolan.BDJDebug;
import org.videolan.Logger;

public class BDWindowGraphics extends BDGraphics {
    private static final int MAX_TRACE_SAMPLE_EVENTS = 16;
    private static int traceSampleEventCount;
    private BDRootWindow window;

    BDWindowGraphics(BDWindowGraphics g) {
        super(g);
        window = g.window;
    }

    public BDWindowGraphics(BDRootWindow window) {
        super(window);
        this.window = window;
    }

    public Graphics create() {
        return new BDWindowGraphics(this);
    }

    public void clearRect(int x, int y, int w, int h) {
        if (window == null) return;
        synchronized (window) {
            BDJDebug.traceGraphics(logger,
                                   "BDWindowGraphics.clearRect rect=" + x + "," + y +
                                   " " + w + "x" + h +
                                   BDJDebug.callerSummary());
            super.clearRect(x, y, w, h);
            window.notifyChanged();
        }
    }

    public void fillRect(int x, int y, int w, int h) {
        if (window == null) return;
        synchronized (window) {
            BDJDebug.traceGraphics(logger,
                                   "BDWindowGraphics.fillRect rect=" + x + "," + y +
                                   " " + w + "x" + h +
                                   " color=" + getColor() +
                                   BDJDebug.callerSummary());
            super.fillRect(x, y, w, h);
            window.notifyChanged();
        }
    }

    public void drawRect(int x, int y, int w, int h) {
        if (window == null) return;
        synchronized (window) {
            super.drawRect(x, y, w, h);
            window.notifyChanged();
        }
    }

    public void drawLine(int x1, int y1, int x2, int y2) {
        if (window == null) return;
        synchronized (window) {
            super.drawLine(x1, y1, x2, y2);
            window.notifyChanged();
        }
    }

    public void copyArea(int x, int y, int w, int h, int dx, int dy) {
        if (window == null) return;
        synchronized (window) {
            super.copyArea(x, y, w, h, dx, dy);
            window.notifyChanged();
        }
    }

    public void drawPolyline(int xPoints[], int yPoints[], int nPoints) {
        if (window == null) return;
        synchronized (window) {
            super.drawPolyline(xPoints, yPoints, nPoints);
            window.notifyChanged();
        }
    }

    public void drawPolygon(int xPoints[], int yPoints[], int nPoints) {
        if (window == null) return;
        synchronized (window) {
            super.drawPolygon(xPoints, yPoints, nPoints);
            window.notifyChanged();
        }
    }

    public void fillPolygon(int xPoints[], int yPoints[], int nPoints) {
        if (window == null) return;
        synchronized (window) {
            super.fillPolygon(xPoints, yPoints, nPoints);
            window.notifyChanged();
        }
    }

    public void drawOval(int x, int y, int w, int h) {
        if (window == null) return;
        synchronized (window) {
            super.drawOval(x, y, w, h);
            window.notifyChanged();
        }
    }

    public void fillOval(int x, int y, int w, int h) {
        if (window == null) return;
        synchronized (window) {
            super.fillOval(x, y, w, h);
            window.notifyChanged();
        }
    }

    public void drawArc(int x, int y, int w, int h, int startAngle, int endAngle) {
        if (window == null) return;
        synchronized (window) {
            super.drawArc(x, y, w, h, startAngle, endAngle);
            window.notifyChanged();
        }
    }

    public void fillArc(int x, int y, int w, int h, int startAngle, int endAngle) {
        if (window == null) return;
        synchronized (window) {
            super.fillArc(x, y, w, h, startAngle, endAngle);
            window.notifyChanged();
        }
    }

    public void drawRoundRect(int x, int y, int w, int h, int arcWidth, int arcHeight) {
        if (window == null) return;
        synchronized (window) {
            super.drawRoundRect(x, y, w, h, arcWidth, arcHeight);
            window.notifyChanged();
        }
    }

    public void fillRoundRect(int x, int y, int w, int h, int arcWidth, int arcHeight) {
        if (window == null) return;
        synchronized (window) {
            super.fillRoundRect(x, y, w, h, arcWidth, arcHeight);
            window.notifyChanged();
        }
    }

    protected void drawStringN(long ftFace, String string, int x, int y, int rgb) {
        if (window == null) return;
        synchronized (window) {
            BDJDebug.traceGraphics(logger,
                                   "BDWindowGraphics.drawStringN text=\"" + string +
                                   "\" at=" + x + "," + y +
                                   " rgb=0x" + Integer.toHexString(rgb) +
                                   BDJDebug.callerSummary());
            super.drawStringN(ftFace, string, x, y, rgb);
            window.notifyChanged();
        }
    }

    public void dispose() {
        super.dispose();
        window = null;
    }

    public boolean drawImageN(Image img,
        int dx, int dy, int dw, int dh,
        int sx, int sy, int sw, int sh,
        boolean flipX, boolean flipY,
        Color bg, ImageObserver observer) {

        if (window == null) return true;

        synchronized (window) {
            String srcInfo = "";
            BDImage srcImage = null;
            if (img instanceof BDImage) {
                srcImage = (BDImage) img;
                srcInfo = " sourceSize=" + srcImage.getWidth() + "x" + srcImage.getHeight() +
                          " sourceDirty=" + srcImage.getDirtyArea() +
                          " sourceComponent=" + srcImage.getComponent();
            }
            String geometryBefore = traceGeometryState();
            String dirtyBefore = traceDirtyState();
            boolean complete = super.drawImageN(
                img, dx, dy, dw, dh, sx, sy, sw, sh,
                flipX, flipY,
                bg, observer);
            BDJDebug.traceGraphics(logger,
                                   "BDWindowGraphics.drawImageN complete=" + complete +
                                   " dst=" + dx + "," + dy + " " + dw + "x" + dh +
                                   " src=" + sx + "," + sy + " " + sw + "x" + sh +
                                   " flip=" + flipX + "/" + flipY +
                                    " image=" + img +
                                   geometryBefore +
                                   dirtyBefore +
                                   " dirtyAfter=" + traceDirtyState() +
                                   srcInfo +
                                   BDJDebug.callerSummary());
            if (complete) {
                tracePixelSamples(srcImage, dx, dy, dw, dh, sx, sy, sw, sh);
            }
            if (complete) {
                window.notifyChanged();
            }
            return complete;
        }
    }

    private void tracePixelSamples(BDImage image,
                                   int dx, int dy, int dw, int dh,
                                   int sx, int sy, int sw, int sh) {
        if (traceSampleEventCount >= MAX_TRACE_SAMPLE_EVENTS) {
            return;
        }
        if (image == null) {
            return;
        }
        if (image.getWidth() != 1920 || image.getHeight() != 1080) {
            return;
        }
        if (window.getWidth() != 1920 || window.getHeight() != 1080) {
            return;
        }

        String phase;
        if (dx == 0 && dy == 0 && dw == 1920 && dh == 1080 &&
            sx == 0 && sy == 0 && sw == 1920 && sh == 1080) {
            phase = "full";
        } else if (dx == 396 && dy == 888 && dw == 1126 && dh == 92) {
            phase = "strip";
        } else {
            return;
        }

        traceSampleEventCount++;

        int[] srcBuffer = image.getBdBackBuffer();
        int[] windowBuffer = window.getBdBackBuffer();
        if (srcBuffer == null || windowBuffer == null) {
            return;
        }

        BDJDebug.traceGraphics(
            logger,
            "BDWindowGraphics.pixelSamples phase=" + phase +
            " dst=" + dx + "," + dy + " " + dw + "x" + dh +
            " src=" + sx + "," + sy + " " + sw + "x" + sh +
            " srcSamples=" + sampleSummary(srcBuffer, image.getWidth(), image.getHeight()) +
            " windowSamples=" + sampleSummary(windowBuffer, window.getWidth(), window.getHeight()) +
            BDJDebug.callerSummary());
    }

    private static String sampleSummary(int[] buffer, int width, int height) {
        return pointSample(buffer, width, height, 0, 0) +
               "," + pointSample(buffer, width, height, 960, 540) +
               "," + pointSample(buffer, width, height, 500, 920) +
               "," + pointSample(buffer, width, height, 1000, 920) +
               "," + pointSample(buffer, width, height, 1300, 920);
    }

    private static String pointSample(int[] buffer, int width, int height, int x, int y) {
        if (buffer == null || x < 0 || y < 0 || x >= width || y >= height) {
            return x + ":" + y + "=out";
        }
        return x + ":" + y + "=" + argbString(buffer[y * width + x]);
    }

    private static String argbString(int value) {
        String hex = Integer.toHexString(value);
        if (hex.length() >= 8) {
            return "0x" + hex;
        }
        StringBuilder builder = new StringBuilder("0x");
        for (int i = hex.length(); i < 8; ++i) {
            builder.append('0');
        }
        builder.append(hex);
        return builder.toString();
    }

    private static final Logger logger = Logger.getLogger(BDWindowGraphics.class.getName());
}
