/*
 * This file is part of libbluray
 * Copyright (C) 2010  William Hahne
 * Copyright (C) 2019  Petri Hintukainen <phintuka@users.sourceforge.net>
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

import org.blurayx.s3d.ui.HGraphicsConfigurationS3D;
import org.blurayx.s3d.ui.HGraphicsConfigTemplateS3D;
import org.blurayx.uhd.ui.HGraphicsConfigurationTemplateUHD;

import java.awt.Dimension;

import org.videolan.GUIManager;
import org.videolan.Libbluray;
import org.videolan.Logger;

public class HGraphicsDevice extends HScreenDevice {
    protected HGraphicsDevice() {
        boolean is_p6 = isProfile6();
        boolean is_p5 = isProfile5();
        int length = HScreenConfigTemplate.defaultConfig.length;
        hgcArray = new HGraphicsConfiguration[length];
        for (int i = 0; i < length; i++) {
            HGraphicsConfigTemplate hgct;
            if (is_p6) {
                hgct = new HGraphicsConfigurationTemplateUHD();
            } else if (is_p5) {
                hgct = new HGraphicsConfigTemplateS3D();
            } else {
                hgct = new HGraphicsConfigTemplate();
            }
            HScreenConfigTemplate.initDefaultConfigTemplate(hgct, i);
            if (is_p5) {
                hgcArray[i] = new HGraphicsConfigurationS3D(hgct);
            } else {
                hgcArray[i] = new HGraphicsConfiguration(hgct);
            }
        }
        hgc = hgcArray[0];
        publishGraphicsS3DState(hgc);
        logger.info("TRACE s3d-device device=graphics profile5=" + is_p5 +
                    " profile6=" + is_p6 +
                    " configCount=" + length +
                    " sampleConfig=" + describeConfig(hgc));
    }

    public HGraphicsConfiguration[] getConfigurations() {
        return hgcArray;
    }

    public HGraphicsConfiguration getDefaultConfiguration() {
        logger.info("TRACE s3d-device device=graphics op=getDefaultConfiguration selected=" +
                    describeConfig(hgcArray[0]));
        return hgcArray[0];
    }

    public HGraphicsConfiguration getBestConfiguration(HGraphicsConfigTemplate hgct) {
        int score = -1;
        HGraphicsConfiguration hgc = null;
        for (int i = 0; i < hgcArray.length; i++)
            if (hgct.match(hgcArray[i]) > score)
                hgc = hgcArray[i];
        logger.info("TRACE s3d-device device=graphics op=getBestConfiguration requestedTemplate=" +
                    describeTemplate(hgct) +
                    " selected=" + describeConfig(hgc));
        return hgc;
    }

    public HGraphicsConfiguration getBestConfiguration(HGraphicsConfigTemplate hgcta[]) {
        int score = -1;
        HGraphicsConfiguration hgc = null;
        for (int i = 0; i < hgcArray.length; i++)
            for (int j = 0; j < hgcta.length; j++)
                if (hgcta[j].match(hgcArray[i]) > score)
                    hgc = hgcArray[i];
        logger.info("TRACE s3d-device device=graphics op=getBestConfigurationArray requestedTemplates=" +
                    describeTemplates(hgcta) +
                    " selected=" + describeConfig(hgc));
        return hgc;
    }

    public HGraphicsConfiguration getCurrentConfiguration() {
        logger.info("TRACE s3d-device device=graphics op=getCurrentConfiguration selected=" +
                    describeConfig(hgc));
        return hgc;
    }

    public boolean setGraphicsConfiguration(HGraphicsConfiguration hgc)
            throws SecurityException, HPermissionDeniedException,
            HConfigurationException {
        if (hgc == null) {
            throw new IllegalArgumentException("HGraphicsConfiguration cannot be null");
        }
        logger.info("TRACE s3d-device device=graphics op=setGraphicsConfiguration current=" +
                    describeConfig(this.hgc) +
                    " requested=" + describeConfig(hgc));
        if (this.hgc == hgc) {
            return true;
        }

        GUIManager mgr = GUIManager.getInstance();
        Dimension d = hgc.getPixelResolution();
        int curWidth = mgr.getWidth();
        int curHeight = mgr.getHeight();

        if (curWidth != d.width || curHeight != d.height) {
            logger.info("Request to switch graphics resolution from " + curWidth + "x" + curHeight +
                        " to " + d.width + "x" + d.height);

            if (mgr.isVisible()) {
                mgr.setVisible(false);
                mgr.setBounds(0, 0, d.width, d.height);
                mgr.setVisible(true);
            } else {
                mgr.setBounds(0, 0, d.width, d.height);
            }

            curWidth = mgr.getWidth();
            curHeight = mgr.getHeight();
            if (curWidth != d.width || curHeight != d.height) {
                logger.error("Request to switch graphics resolution from " + curWidth + "x" + curHeight +
                            " to " + d.width + "x" + d.height + " FAILED");
                return false;
            }
        }

        this.hgc = hgc;
        publishGraphicsS3DState(hgc);
        return true;
    }

    private static void publishGraphicsS3DState(HGraphicsConfiguration config) {
        int mode = Libbluray.IG_S3D_MODE_UNKNOWN;
        boolean modeValid = false;
        int offset = 0;
        boolean offsetValid = false;

        if (config instanceof HGraphicsConfigurationS3D) {
            HGraphicsConfigurationS3D s3dConfig = (HGraphicsConfigurationS3D)config;
            mode = s3dConfig.getS3DModeValue();
            modeValid = (mode != Libbluray.IG_S3D_MODE_UNKNOWN);
            offset = s3dConfig.getOffsetValue();
            offsetValid = s3dConfig.hasExplicitOffsetValue();
        } else if (config != null) {
            mode = Libbluray.IG_S3D_MODE_TWOD_OUTPUT;
            modeValid = true;
        }

        logger.info("TRACE s3d-device publish-state config=" + describeConfig(config) +
                    " mode=" + mode +
                    " modeValid=" + modeValid +
                    " offset=" + offset +
                    " offsetValid=" + offsetValid);
        Libbluray.setGraphicsS3DState(mode, modeValid, offset, offsetValid);
    }

    private static String describeConfig(HGraphicsConfiguration config) {
        if (config == null) {
            return "<null>";
        }
        return config.getClass().getName() +
               "{template=" + describeTemplate(config.getConfigTemplate()) +
               ", resolution=" + config.getPixelResolution() +
               "}";
    }

    private static String describeTemplate(HGraphicsConfigTemplate template) {
        if (template == null) {
            return "<null>";
        }
        return template.getClass().getName() +
               "@" + Integer.toHexString(System.identityHashCode(template));
    }

    private static String describeTemplates(HGraphicsConfigTemplate[] templates) {
        if (templates == null) {
            return "<null>";
        }
        StringBuffer sb = new StringBuffer("[");
        for (int i = 0; i < templates.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(describeTemplate(templates[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    private HGraphicsConfiguration[] hgcArray;
    private HGraphicsConfiguration hgc;

    private static final Logger logger = Logger.getLogger(HGraphicsDevice.class.getName());
}
