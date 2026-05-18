/*
 * This file is part of libbluray
 * Copyright (C) 2014  Libbluray
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

package org.blurayx.s3d.ui;

import java.awt.Image;
import java.awt.Rectangle;
import org.videolan.Logger;

public class DirectDrawS3D {
    private static final Logger LOG = Logger.getLogger("S3DTrace");

    static {
        LOG.info("DirectDrawS3D class loaded");
    }

    public DirectDrawS3D() {
        LOG.info("DirectDrawS3D instance created");
    }

    public void drawStereoscopic(Image leftImage, int leftX, int leftY,
                                 Rectangle[] leftUpdateAreas,
                                 Image rightImage, int rightX, int rightY,
                                 Rectangle[] rightUpdateAreas) {
        LOG.info("DirectDrawS3D.drawStereoscopic left="
                 + describeImage(leftImage) + "@" + leftX + "," + leftY
                 + " leftUpdates=" + describeRects(leftUpdateAreas)
                 + " right=" + describeImage(rightImage) + "@" + rightX + "," + rightY
                 + " rightUpdates=" + describeRects(rightUpdateAreas));
    }

    public void drawStereoscopicImages(Image[] leftImages, int[] leftXs, int[] leftYs,
                                       Rectangle[] leftUpdateAreas,
                                       Image[] rightImages, int[] rightXs, int[] rightYs,
                                       Rectangle[] rightUpdateAreas) {
        LOG.info("DirectDrawS3D.drawStereoscopicImages leftImages="
                 + describeImages(leftImages) + " leftXs=" + describeInts(leftXs)
                 + " leftYs=" + describeInts(leftYs)
                 + " leftUpdates=" + describeRects(leftUpdateAreas)
                 + " rightImages=" + describeImages(rightImages)
                 + " rightXs=" + describeInts(rightXs)
                 + " rightYs=" + describeInts(rightYs)
                 + " rightUpdates=" + describeRects(rightUpdateAreas));
    }

    public static DirectDrawS3D getInstance() {
        LOG.info("DirectDrawS3D.getInstance requested");
        org.videolan.Logger.unimplemented("DirectDrawS3D", "getInstance");
        return null;
    }

    private static String describeImage(Image image) {
        if (image == null) {
            return "null";
        }
        return image.getClass().getName() + "@"
               + Integer.toHexString(System.identityHashCode(image));
    }

    private static String describeImages(Image[] images) {
        if (images == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < images.length; ++i) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(describeImage(images[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String describeInts(int[] values) {
        if (values == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < values.length; ++i) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private static String describeRects(Rectangle[] rects) {
        if (rects == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < rects.length; ++i) {
            if (i > 0) {
                sb.append(", ");
            }
            Rectangle rect = rects[i];
            if (rect == null) {
                sb.append("null");
            } else {
                sb.append(rect.x).append(',').append(rect.y)
                  .append(' ').append(rect.width).append('x').append(rect.height);
            }
        }
        sb.append(']');
        return sb.toString();
    }
}
