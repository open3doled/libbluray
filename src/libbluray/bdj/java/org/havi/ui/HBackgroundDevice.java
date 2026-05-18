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

import org.blurayx.s3d.ui.HBackgroundConfigurationS3D;
import org.blurayx.s3d.ui.HBackgroundConfigTemplateS3D;
import org.blurayx.uhd.ui.HBackgroundConfigurationTemplateUHD;
import org.videolan.BDJDebug;
import org.videolan.Logger;

import java.awt.Color;

public class HBackgroundDevice extends HScreenDevice {
    protected HBackgroundDevice() {
        boolean is_p6 = isProfile6();
        boolean is_p5 = isProfile5();
        int length = HScreenConfigTemplate.defaultConfig.length;
        hbcArray = new HBackgroundConfiguration[length];
        for (int i = 0; i < length; i++) {
            HBackgroundConfigTemplate hbct;
            if (is_p6) {
                hbct = new HBackgroundConfigurationTemplateUHD();
            } else if (is_p5) {
                hbct = new HBackgroundConfigTemplateS3D();
            } else {
                hbct = new HBackgroundConfigTemplate();
            }
            HScreenConfigTemplate.initDefaultConfigTemplate(hbct, i);
            if (is_p5) {
                hbcArray[i] = new HBackgroundConfigurationS3D(hbct, new Color(0, 0, 0, 0));
            } else {
                hbcArray[i] = new HStillImageBackgroundConfiguration(hbct, new Color(0, 0, 0, 0));
            }
        }
        hbc = hbcArray[0];
        logger.info("TRACE s3d-device device=background profile5=" + is_p5 +
                    " profile6=" + is_p6 +
                    " configCount=" + length +
                    " sampleConfig=" + describeConfig(hbc));
    }

    public HBackgroundConfiguration[] getConfigurations() {
        return hbcArray;
    }

    public HBackgroundConfiguration getDefaultConfiguration() {
        logger.info("TRACE s3d-device device=background op=getDefaultConfiguration selected=" +
                    describeConfig(hbcArray[0]));
        return hbcArray[0];
    }

    public HBackgroundConfiguration getBestConfiguration(HBackgroundConfigTemplate hbct) {
        int score = -1;
        HBackgroundConfiguration hbc = null;
        for (int i = 0; i < hbcArray.length; i++)
            if (hbct.match(hbcArray[i]) > score)
                hbc = hbcArray[i];
        logger.info("TRACE s3d-device device=background op=getBestConfiguration requestedTemplate=" +
                    describeTemplate(hbct) +
                    " selected=" + describeConfig(hbc));
        return hbc;
    }

    public HBackgroundConfiguration getBestConfiguration(HBackgroundConfigTemplate hbcta[]) {
        int score = -1;
        HBackgroundConfiguration hbc = null;
        for (int i = 0; i < hbcArray.length; i++)
            for (int j = 0; j < hbcta.length; j++)
                if (hbcta[j].match(hbcArray[i]) > score)
                    hbc = hbcArray[i];
        logger.info("TRACE s3d-device device=background op=getBestConfigurationArray requestedTemplates=" +
                    describeTemplates(hbcta) +
                    " selected=" + describeConfig(hbc));
        return hbc;
    }

    public HBackgroundConfiguration getCurrentConfiguration() {
        logger.info("TRACE s3d-device device=background op=getCurrentConfiguration selected=" +
                    describeConfig(hbc));
        return hbc;
    }

    public boolean setBackgroundConfiguration(HBackgroundConfiguration hbc)
            throws SecurityException, HPermissionDeniedException,
            HConfigurationException {
        BDJDebug.traceGraphics(logger,
                               "HBackgroundDevice.setBackgroundConfiguration current=" +
                               describeConfig(this.hbc) +
                               " new=" + describeConfig(hbc) +
                               BDJDebug.callerSummary());
        org.videolan.Logger.unimplemented("HBackgroundDevide", "setBackgroundConfiguration()");
        this.hbc = hbc;
        return true;
    }

    private static String describeConfig(HBackgroundConfiguration config) {
        if (config == null) {
            return "<null>";
        }
        Color color = config.getColor();
        return config.getClass().getName() +
               "{color=" + color +
               ", template=" + describeTemplate(config.getConfigTemplate()) +
               "}";
    }

    private static String describeTemplate(HBackgroundConfigTemplate template) {
        if (template == null) {
            return "<null>";
        }
        return template.getClass().getName() +
               "@" + Integer.toHexString(System.identityHashCode(template));
    }

    private static String describeTemplates(HBackgroundConfigTemplate[] templates) {
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

    private HBackgroundConfiguration[] hbcArray;
    private HBackgroundConfiguration hbc;
    private static final Logger logger = Logger.getLogger(HBackgroundDevice.class.getName());
}
