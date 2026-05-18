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

import org.havi.ui.HConfigurationException;
import org.havi.ui.HGraphicsConfigTemplate;
import org.havi.ui.HGraphicsConfiguration;
import org.havi.ui.HPermissionDeniedException;
import org.videolan.Libbluray;
import org.videolan.Logger;

public class HGraphicsConfigurationS3D extends HGraphicsConfiguration {
    private static final Logger LOG = Logger.getLogger("S3DTrace");
    private final int s3dMode;
    private int offsetValue = 0;
    private boolean offsetValueValid = false;

    static {
        LOG.info("HGraphicsConfigurationS3D class loaded");
    }

    public HGraphicsConfigurationS3D(HGraphicsConfigTemplate hgct) {
        super(hgct);
        this.s3dMode = resolveS3DMode(hgct);
        LOG.info("HGraphicsConfigurationS3D instance created template="
                 + describeTemplate(hgct) +
                 " mode=" + describeMode(s3dMode));
    }

    public int getOffsetValue() {
        LOG.info("HGraphicsConfigurationS3D.getOffsetValue value=" + offsetValue +
                 " valid=" + offsetValueValid);
        return offsetValue;
    }

    public void setOffsetValue(int offset) throws HPermissionDeniedException, HConfigurationException {
        LOG.info("HGraphicsConfigurationS3D.setOffsetValue offset=" + offset);
        offsetValue = offset;
        offsetValueValid = true;
        Libbluray.setGraphicsS3DState(s3dMode,
                                      s3dMode != Libbluray.IG_S3D_MODE_UNKNOWN,
                                      offsetValue, true);
    }

    public int getS3DModeValue() {
        return s3dMode;
    }

    public boolean hasExplicitOffsetValue() {
        return offsetValueValid;
    }

    private static int resolveS3DMode(HGraphicsConfigTemplate template) {
        if (!(template instanceof HGraphicsConfigTemplateS3D)) {
            return Libbluray.IG_S3D_MODE_UNKNOWN;
        }

        Object preference =
            template.getPreferenceObject(HGraphicsConfigTemplateS3D.S3D);
        if (preference == S3DProperty.TWOD_OUTPUT) {
            return Libbluray.IG_S3D_MODE_TWOD_OUTPUT;
        }
        if (preference == S3DProperty.ONE_PLANE) {
            return Libbluray.IG_S3D_MODE_ONE_PLANE;
        }
        if (preference == S3DProperty.TWO_PLANES) {
            return Libbluray.IG_S3D_MODE_TWO_PLANES;
        }
        return Libbluray.IG_S3D_MODE_UNKNOWN;
    }

    private static String describeMode(int mode) {
        switch (mode) {
        case Libbluray.IG_S3D_MODE_TWOD_OUTPUT:
            return "TWOD_OUTPUT";
        case Libbluray.IG_S3D_MODE_ONE_PLANE:
            return "ONE_PLANE";
        case Libbluray.IG_S3D_MODE_TWO_PLANES:
            return "TWO_PLANES";
        default:
            return "UNKNOWN";
        }
    }

    private static String describeTemplate(HGraphicsConfigTemplate template) {
        if (template == null) {
            return "null";
        }
        return template.getClass().getName() + "@"
               + Integer.toHexString(System.identityHashCode(template));
    }
}
