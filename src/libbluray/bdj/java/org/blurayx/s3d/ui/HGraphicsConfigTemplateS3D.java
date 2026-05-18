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

import org.havi.ui.HGraphicsConfigTemplate;
import org.videolan.Logger;

public class HGraphicsConfigTemplateS3D extends HGraphicsConfigTemplate {
    private static final Logger LOG = Logger.getLogger("S3DTrace");
    public static final int S3D = 17;

    static {
        LOG.info("HGraphicsConfigTemplateS3D class loaded");
    }

    public HGraphicsConfigTemplateS3D() {
        LOG.info("HGraphicsConfigTemplateS3D instance created");
    }

    protected int getPreferenceCount() {
        LOG.info("HGraphicsConfigTemplateS3D.getPreferenceCount");
        return super.getPreferenceCount() + 1;
    }

    protected int getPreferenceObjectCount() {
        LOG.info("HGraphicsConfigTemplateS3D.getPreferenceObjectCount");
        return super.getPreferenceObjectCount() + 1;
    }

    protected int getPreferenceIndex(int preference) {
        LOG.info("HGraphicsConfigTemplateS3D.getPreferenceIndex preference=" + preference);
        if (preference == S3D)
            return super.getPreferenceCount();
        return super.getPreferenceIndex(preference);
    }

    protected int getPreferenceObjectIndex(int preference) {
        LOG.info("HGraphicsConfigTemplateS3D.getPreferenceObjectIndex preference=" + preference);
        if (preference == S3D)
            return super.getPreferenceObjectCount();
        return super.getPreferenceObjectIndex(preference);
    }
}
