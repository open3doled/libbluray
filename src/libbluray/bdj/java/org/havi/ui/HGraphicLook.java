/*
 * This file is part of libbluray
 * Copyright (C) 2010  William Hahne
 * Copyright (C) 2026  libbluray project
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
 *
 * Rendering implementation based on XletView by Martin Sveden.
 */

package org.havi.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.Insets;

public class HGraphicLook implements HExtendedLook {

    private static final Insets DEFAULT_INSETS = new Insets(2, 2, 2, 2);

    public HGraphicLook() {
    }

    public void fillBackground(Graphics g, HVisible visible, int state) {
        if (visible.getBackgroundMode() == HVisible.BACKGROUND_FILL) {
            Color bg = visible.getBackground();
            if (bg != null) {
                Dimension size = visible.getSize();
                g.setColor(bg);
                g.fillRect(0, 0, size.width, size.height);
            }
        }
    }

    public void renderBorders(Graphics g, HVisible visible, int state) {
        if (!visible.getBordersEnabled()) {
            return;
        }

        // Draw focus border when component is focused
        if ((state & HState.FOCUSED_STATE_BIT) != 0) {
            Color fg = visible.getForeground();
            if (fg != null) {
                Dimension size = visible.getSize();
                Insets insets = getInsets(visible);
                g.setColor(fg);

                // Top border
                g.fillRect(0, 0, size.width, insets.top);

                // Right border
                g.fillRect(size.width - insets.right, 0, insets.right, size.height);

                // Bottom border
                g.fillRect(0, size.height - insets.bottom, size.width, insets.bottom);

                // Left border
                g.fillRect(0, 0, insets.left, size.height);
            }
        }
    }

    public void renderVisible(Graphics g, HVisible visible, int state) {
        // Get the image for this state
        Image imageToDraw = visible.getGraphicContent(state);
        if (imageToDraw != null) {
            drawImage(g, imageToDraw, visible);
        }
    }

    /**
     * Main rendering method that draws the HVisible according to the HAVI spec.
     * Order of operations:
     * 1. Fill background (if BACKGROUND_FILL mode)
     * 2. Render state-based content
     * 3. Render borders (if focused and borders enabled)
     */
    public void showLook(Graphics g, HVisible visible, int state) {
        // 1. Fill background
        fillBackground(g, visible, state);

        // 2. Render the graphic content for this state
        renderVisible(g, visible, state);

        // 3. Render focus borders
        renderBorders(g, visible, state);
    }

    /**
     * Draws an image onto the HVisible with proper alignment.
     * Uses the HVisible's horizontal and vertical alignment settings.
     *
     * Note: This implementation only supports RESIZE_NONE mode as per HAVI spec
     * minimum requirements. Scaling support is optional.
     *
     * @param g Graphics context
     * @param imageToDraw The image to draw
     * @param visible The HVisible owner
     * @return true if the image was drawn successfully
     */
    protected static boolean drawImage(Graphics g, Image imageToDraw, HVisible visible) {
        int hAlign = visible.getHorizontalAlignment();
        int vAlign = visible.getVerticalAlignment();

        int vWidth = visible.getWidth();
        int vHeight = visible.getHeight();

        int imgWidth = imageToDraw.getWidth(visible);
        int imgHeight = imageToDraw.getHeight(visible);

        // If image dimensions aren't available yet, use component size
        if (imgWidth <= 0) imgWidth = vWidth;
        if (imgHeight <= 0) imgHeight = vHeight;

        /*
         * Per HAVI spec: "Note that the results of applying the VALIGN_JUSTIFY
         * and HALIGN_JUSTIFY alignment modes for graphical content are defined
         * to be identical to VALIGN_CENTER and HALIGN_CENTER modes respectively,
         * as justification is meaningless in this context."
         */

        int drawX = 0;
        int drawY = 0;

        // Calculate horizontal position
        switch (hAlign) {
            case HVisible.HALIGN_CENTER:
            case HVisible.HALIGN_JUSTIFY:
                drawX = (vWidth - imgWidth) / 2;
                break;
            case HVisible.HALIGN_LEFT:
                drawX = 0;
                break;
            case HVisible.HALIGN_RIGHT:
                drawX = vWidth - imgWidth;
                break;
        }

        // Calculate vertical position
        switch (vAlign) {
            case HVisible.VALIGN_CENTER:
            case HVisible.VALIGN_JUSTIFY:
                drawY = (vHeight - imgHeight) / 2;
                break;
            case HVisible.VALIGN_TOP:
                drawY = 0;
                break;
            case HVisible.VALIGN_BOTTOM:
                drawY = vHeight - imgHeight;
                break;
        }

        return g.drawImage(imageToDraw, drawX, drawY, visible);
    }

    public void widgetChanged(HVisible visible, HChangeData[] changes) {
        /*
         * Per HAVI spec: "Note that implementations of HLook may not actually
         * implement more efficient drawing code for a given hint. In particular,
         * simply repainting the entire HVisible is a valid implementation option."
         *
         * "A minimum implementation of this method could simply call visible.repaint()"
         */
        if (visible.isVisible()) {
            visible.repaint();
        }
    }

    public Dimension getMinimumSize(HVisible visible) {
        // Return the size of the NORMAL_STATE content, or component size if none
        Image img = visible.getGraphicContent(HState.NORMAL_STATE);
        if (img != null) {
            int w = img.getWidth(visible);
            int h = img.getHeight(visible);
            if (w > 0 && h > 0) {
                Insets insets = getInsets(visible);
                return new Dimension(w + insets.left + insets.right,
                                   h + insets.top + insets.bottom);
            }
        }
        return visible.getSize();
    }

    public Dimension getPreferredSize(HVisible visible) {
        // Same as minimum for graphic content
        return getMinimumSize(visible);
    }

    public Dimension getMaximumSize(HVisible visible) {
        // No upper limit - return component size
        return visible.getSize();
    }

    public boolean isOpaque(HVisible visible) {
        // Component is opaque if background fill is enabled and has opaque background color
        if (visible.getBackgroundMode() != HVisible.BACKGROUND_FILL) {
            return false;
        }

        Color bg = visible.getBackground();
        if ((bg == null) || (bg.getAlpha() < 255)) {
            return false;
        }

        return true;
    }

    public Insets getInsets(HVisible visible) {
        return DEFAULT_INSETS;
    }
}
