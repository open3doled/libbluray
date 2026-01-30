/*
 * This file is part of libbluray
 * Copyright (C) 2010  William Hahne
 * Copyright (C) 2013  Petri Hintukainen <phintuka@users.sourceforge.net>
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
 */

package org.havi.ui;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Insets;

import org.videolan.Logger;

public class HTextLook implements HExtendedLook {

    private static final Insets DEFAULT_INSETS = new Insets(2, 2, 2, 2);
    private static final Insets NO_INSETS = new Insets(0, 0, 0, 0);

    public HTextLook() {
    }

    public void fillBackground(Graphics g, HVisible visible, int state) {
        if (visible.getBackgroundMode() == HVisible.BACKGROUND_FILL) {
            Color color = visible.getBackground();
            if (color != null) {
                Dimension dimension = visible.getSize();
                g.setColor(color);
                g.fillRect(0, 0, dimension.width, dimension.height);
            }
        }
    }

    public void renderBorders(Graphics g, HVisible visible, int state) {
        if (!visible.getBordersEnabled()) {
            return;
        }

        // Only draw borders when focused
        if ((state & HState.FOCUSED_STATE_BIT) == 0) {
            return;
        }

        Insets insets = DEFAULT_INSETS;
        Color fg = visible.getForeground();
        Dimension dimension = visible.getSize();

        if (fg != null) {
            g.setColor(fg);
            // Top border
            g.fillRect(0, 0, dimension.width, insets.top);
            // Right border
            g.fillRect(dimension.width - insets.right, 0, insets.right, dimension.height);
            // Bottom border
            g.fillRect(0, dimension.height - insets.bottom, dimension.width, insets.bottom);
            // Left border
            g.fillRect(0, 0, insets.left, dimension.height);
        }
    }

    public void renderVisible(Graphics g, HVisible visible, int state) {
        String text = visible.getTextContent(state);
        if (text == null || text.length() == 0) {
            return;
        }

        Dimension size = visible.getSize();
        Insets insets = getInsets(visible);

        // Calculate available area for text
        int availWidth = size.width - insets.left - insets.right;
        int availHeight = size.height - insets.top - insets.bottom;

        if (availWidth <= 0 || availHeight <= 0) {
            return;
        }

        // Set font and get metrics
        Font font = visible.getFont();
        if (font != null) {
            g.setFont(font);
        }
        FontMetrics fm = g.getFontMetrics();

        // Set text color
        Color fg = visible.getForeground();
        if (fg != null) {
            g.setColor(fg);
        }

        // Use HTextLayoutManager if available
        HTextLayoutManager tlm = visible.getTextLayoutManager();
        if (tlm != null) {
            tlm.render(text, g, visible,
                      new java.awt.Insets(insets.top, insets.left, insets.bottom, insets.right));
            return;
        }

        // Fallback: Simple text rendering with alignment
        int textWidth = fm.stringWidth(text);
        int textHeight = fm.getAscent();

        int hAlign = visible.getHorizontalAlignment();
        int vAlign = visible.getVerticalAlignment();

        // Calculate X position
        int x;
        switch (hAlign) {
            case HVisible.HALIGN_CENTER:
            case HVisible.HALIGN_JUSTIFY:
                x = insets.left + (availWidth - textWidth) / 2;
                break;
            case HVisible.HALIGN_RIGHT:
                x = insets.left + availWidth - textWidth;
                break;
            case HVisible.HALIGN_LEFT:
            default:
                x = insets.left;
                break;
        }

        // Calculate Y position (baseline)
        int y;
        switch (vAlign) {
            case HVisible.VALIGN_CENTER:
            case HVisible.VALIGN_JUSTIFY:
                y = insets.top + (availHeight + textHeight) / 2 - fm.getDescent();
                break;
            case HVisible.VALIGN_BOTTOM:
                y = size.height - insets.bottom - fm.getDescent();
                break;
            case HVisible.VALIGN_TOP:
            default:
                y = insets.top + textHeight;
                break;
        }

        g.drawString(text, x, y);
    }

    public void showLook(Graphics g, HVisible visible, int state) {
        fillBackground(g, visible, state);
        renderVisible(g, visible, state);
        renderBorders(g, visible, state);
    }

    public void widgetChanged(HVisible visible, HChangeData[] changes) {
        if (visible.isVisible()) {
            visible.repaint();
        }
    }

    public Dimension getMinimumSize(HVisible visible) {
        String text = visible.getTextContent(HState.NORMAL_STATE);
        if (text != null && text.length() > 0) {
            Font font = visible.getFont();
            if (font != null) {
                FontMetrics fm = visible.getFontMetrics(font);
                if (fm != null) {
                    Insets insets = getInsets(visible);
                    int w = fm.stringWidth(text) + insets.left + insets.right;
                    int h = fm.getHeight() + insets.top + insets.bottom;
                    return new Dimension(w, h);
                }
            }
        }
        return visible.getSize();
    }

    public Dimension getPreferredSize(HVisible visible) {
        return getMinimumSize(visible);
    }

    public Dimension getMaximumSize(HVisible visible) {
        return visible.getSize();
    }

    public boolean isOpaque(HVisible visible) {
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
        if (!visible.getBordersEnabled()) {
            return NO_INSETS;
        }
        return DEFAULT_INSETS;
    }

    private static final Logger logger = Logger.getLogger(HTextLook.class.getName());
}
