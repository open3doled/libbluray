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

import org.blurayx.s3d.ui.HVideoConfigTemplateS3D;
import org.blurayx.uhd.ui.HVideoConfigurationTemplateUHD;
import org.videolan.Logger;

public class HVideoDevice extends HScreenDevice {
    protected HVideoDevice() {
        boolean is_p6 = isProfile6();
        boolean is_p5 = isProfile5();
        int length = HScreenConfigTemplate.defaultConfig.length;
        hvcArray = new HVideoConfiguration[length];
        for (int i = 0; i < length; i++) {
            HVideoConfigTemplate hvct;
            if (is_p6) {
                hvct = new HVideoConfigurationTemplateUHD();
            } else if (is_p5) {
                hvct = new HVideoConfigTemplateS3D();
            } else {
                hvct = new HVideoConfigTemplate();
            }
            HScreenConfigTemplate.initDefaultConfigTemplate(hvct, i);
            hvcArray[i] = new HVideoConfiguration(hvct);
        }
        hvc = hvcArray[0];
        logger.info("TRACE s3d-device device=video profile5=" + is_p5 +
                    " profile6=" + is_p6 +
                    " configCount=" + length +
                    " sampleConfig=" + describeConfig(hvc));
    }

    public HVideoConfiguration[] getConfigurations() {
        return hvcArray;
    }

    public HVideoConfiguration getDefaultConfiguration() {
        logger.info("TRACE s3d-device device=video op=getDefaultConfiguration selected=" +
                    describeConfig(hvcArray[0]));
        return hvcArray[0];
    }

    public HVideoConfiguration getBestConfiguration(HVideoConfigTemplate hvct) {
        int score = -1;
        HVideoConfiguration hvc = null;
        for (int i = 0; i < hvcArray.length; i++)
            if (hvct.match(hvcArray[i]) > score)
                hvc = hvcArray[i];
        logger.info("TRACE s3d-device device=video op=getBestConfiguration requestedTemplate=" +
                    describeTemplate(hvct) +
                    " selected=" + describeConfig(hvc));
        return hvc;
    }

    public HVideoConfiguration getBestConfiguration(HVideoConfigTemplate hvcta[]) {
        int score = -1;
        HVideoConfiguration hvc = null;
        for (int i = 0; i < hvcArray.length; i++) 
            for (int j = 0; j < hvcta.length; j++)
                if (hvcta[j].match(hvcArray[i]) > score)
                    hvc = hvcArray[i];
        logger.info("TRACE s3d-device device=video op=getBestConfigurationArray requestedTemplates=" +
                    describeTemplates(hvcta) +
                    " selected=" + describeConfig(hvc));
        return hvc;
    }

    public HVideoConfiguration getCurrentConfiguration() {
        logger.info("TRACE s3d-device device=video op=getCurrentConfiguration selected=" +
                    describeConfig(hvc));
        return hvc;
    }

    public boolean setVideoConfiguration(HVideoConfiguration hvc)
            throws SecurityException, HPermissionDeniedException, HConfigurationException {
        logger.info("TRACE s3d-device device=video op=setVideoConfiguration current=" +
                    describeConfig(this.hvc) +
                    " requested=" + describeConfig(hvc));
        this.hvc = hvc;
        return true;
    }

    public Object getVideoSource() throws SecurityException, HPermissionDeniedException {
        org.videolan.Logger.unimplemented(HVideoDevice.class.getName(), "getVideoSource");
        throw new HPermissionDeniedException();
    }

    public Object getVideoController() throws SecurityException, HPermissionDeniedException {
        org.videolan.Logger.unimplemented(HVideoDevice.class.getName(), "getVideoController");
        throw new HPermissionDeniedException();
    }

    public static final HVideoConfiguration NOT_CONTRIBUTING = null;

    private static String describeConfig(HVideoConfiguration config) {
        if (config == null) {
            return "<null>";
        }
        return config.getClass().getName() +
               "{template=" + describeTemplate(config.getConfigTemplate()) +
               "}";
    }

    private static String describeTemplate(HVideoConfigTemplate template) {
        if (template == null) {
            return "<null>";
        }
        return template.getClass().getName() +
               "@" + Integer.toHexString(System.identityHashCode(template));
    }

    private static String describeTemplates(HVideoConfigTemplate[] templates) {
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

    private HVideoConfiguration[] hvcArray;
    private HVideoConfiguration hvc;
    private static final Logger logger = Logger.getLogger(HVideoDevice.class.getName());
}
