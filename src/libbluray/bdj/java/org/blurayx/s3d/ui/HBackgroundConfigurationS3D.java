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

import java.awt.Color;
import java.io.IOException;
import org.havi.ui.HBackgroundImage;
import org.havi.ui.HBackgroundConfigTemplate;
import org.havi.ui.HConfigurationException;
import org.havi.ui.HPermissionDeniedException;
import org.havi.ui.HScreenRectangle;
import org.havi.ui.HStillImageBackgroundConfiguration;
import org.videolan.Logger;

public class HBackgroundConfigurationS3D extends HStillImageBackgroundConfiguration {
    private static final Logger LOG = Logger.getLogger("S3DTrace");

    static {
        LOG.info("HBackgroundConfigurationS3D class loaded");
    }

    public HBackgroundConfigurationS3D(HBackgroundConfigTemplate hbct, Color color) {
        super(hbct, color);
        LOG.info("HBackgroundConfigurationS3D instance created template="
                 + describeTemplate(hbct) + " color=" + color);
    }

    public void displayImage(HBackgroundImage leftImage,
                             HBackgroundImage rightImage)
        throws IOException, HPermissionDeniedException, HConfigurationException {
        LOG.info("HBackgroundConfigurationS3D.displayImage dual default left="
                 + describeObject(leftImage) + " right=" + describeObject(rightImage));

        displayImage(leftImage, rightImage,
                     new HScreenRectangle(0.0f, 0.0f, 1.0f, 1.0f),
                     new HScreenRectangle(0.0f, 0.0f, 1.0f, 1.0f));
    }

    public void displayImage(HBackgroundImage leftImage,
                             HBackgroundImage rightImage,
                             HScreenRectangle leftUpdateArea,
                             HScreenRectangle rightUpdateArea)
        throws IOException, HPermissionDeniedException, HConfigurationException {
        LOG.info("HBackgroundConfigurationS3D.displayImage dual left="
                 + describeObject(leftImage) + " leftArea=" + describeRect(leftUpdateArea)
                 + " right=" + describeObject(rightImage)
                 + " rightArea=" + describeRect(rightUpdateArea));

        displayImage(leftImage, leftUpdateArea);
        org.videolan.Logger.unimplemented("HBackgroundConfigurationS3D", "displayImage");
    }

    private static String describeObject(Object value) {
        if (value == null) {
            return "null";
        }
        return value.getClass().getName() + "@"
               + Integer.toHexString(System.identityHashCode(value));
    }

    private static String describeRect(HScreenRectangle rect) {
        if (rect == null) {
            return "null";
        }
        return rect.x + "," + rect.y + " " + rect.width + "x" + rect.height;
    }

    private static String describeTemplate(HBackgroundConfigTemplate template) {
        if (template == null) {
            return "null";
        }
        return template.getClass().getName() + "@"
               + Integer.toHexString(System.identityHashCode(template));
    }
}
