/*
 * This file is part of libbluray
 * Copyright (C) 2010  William Hahne
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
package org.bluray.system;

import org.videolan.BDJDebug;
import org.videolan.Libbluray;
import org.videolan.Logger;

public class RegisterAccess {
    private RegisterAccess() {
    }

    public static RegisterAccess getInstance() {
        return instance;
    }

    public int getGPR(int num) {
        if (num < 0 || num > 4095) {
            logger.error("getGPR(" + num + "): invalid GPR");
            throw new IllegalArgumentException("GPR " + num + " is not supported.");
        }

        int value = Libbluray.readGPR(num);
        traceMenuBranchGprAccess("read", num, value, value);
        traceSelectedGprAccess("read", num, value);
        traceBootFlagAccess("read", num, value);
        return value;
    }

    public int getPSR(int num) {
        if (num < 0 || num > 127) {
            logger.error("getPSR(" + num + "): invalid PSR");
            throw new IllegalArgumentException("PSR " + num + " is not supported.");
        }

        int value = Libbluray.readPSR(num);
        traceS3DPsrAccess("read", num, value);
        return value;
    }

    public void setGPR(int num, int value) {
        if (num < 0 || num > 4095) {
            logger.error("setGPR(" + num + ", " + value + "): invalid GPR");
            throw new IllegalArgumentException("GPR " + num + " is not supported.");
        }
        int oldValue = Libbluray.readGPR(num);
        traceMenuBranchGprAccess("write", num, oldValue, value);
        traceSelectedGprAccess("write", num, value);
        traceBootFlagAccess("write", num, value);
        Libbluray.writeGPR(num, value);
    }

    private static void traceMenuBranchGprAccess(String op, int num, int oldValue, int newValue) {
        if (!isMenuBranchGpr(num)) {
            return;
        }
        logger.error("TRACE menuBranchGpr gpr=" + num +
                     " op=" + op +
                     " value=" + newValue +
                     " old=" + oldValue +
                     " thread=" + Thread.currentThread().getName() +
                     BDJDebug.callerSummary());
    }

    private static boolean isMenuBranchGpr(int num) {
        return num == 100 || num == 104 || num == 105 || num == 110;
    }

    private static void traceSelectedGprAccess(String op, int num, int value) {
        if (BDJDebug.tracedGprNum() != num) {
            return;
        }
        BDJDebug.traceRegister(
                logger,
                "gpr" + num + " " + op + " value=" + value +
                " thread=" + Thread.currentThread().getName() +
                BDJDebug.callerSummary());
    }

    private static void traceBootFlagAccess(String op, int num, int value) {
        if (!BDJDebug.lifecycleEnabled() || num != TRACE_GPR_BOOT_FLAG) {
            return;
        }
        BDJDebug.traceLifecycle(
                logger,
                "gpr1889 " + op + " value=" + value +
                " thread=" + Thread.currentThread().getName() +
                BDJDebug.callerSummary());
    }

    private static void traceS3DPsrAccess(String op, int num, int value) {
        if (!isS3DPsr(num)) {
            return;
        }
        logger.error("TRACE s3d-psr psr=" + num +
                     " name=" + describePsr(num) +
                     " op=" + op +
                     " value=0x" + Integer.toHexString(value) +
                     " thread=" + Thread.currentThread().getName() +
                     BDJDebug.callerSummary());
    }

    private static boolean isS3DPsr(int num) {
        return num == PSR_OUTPUT_MODE_PREFERENCE ||
               num == PSR_3D_STATUS ||
               num == PSR_DISPLAY_CAPABILITY ||
               num == PSR_3D_CAPABILITY ||
               num == PSR_PLAYER_PROFILE;
    }

    private static String describePsr(int num) {
        switch (num) {
        case PSR_OUTPUT_MODE_PREFERENCE:
            return "OUTPUT_MODE_PREFERENCE";
        case PSR_3D_STATUS:
            return "3D_STATUS";
        case PSR_DISPLAY_CAPABILITY:
            return "DISPLAY_CAPABILITY";
        case PSR_3D_CAPABILITY:
            return "3D_CAPABILITY";
        case PSR_PLAYER_PROFILE:
            return "PLAYER_PROFILE";
        default:
            return "psr" + num;
        }
    }

    public static final int PSR_AUDIO_STN = 1;
    public static final int PSR_PG_TXTST_STN = 2;
    public static final int PSR_ANGLE_NR = 3;
    public static final int PSR_TITLE_NR = 4;
    public static final int PSR_CHAPTER_NR = 5;
    public static final int PSR_PLAYLIST_ID = 6;
    public static final int PSR_PLAYITEM_ID = 7;
    public static final int PSR_PRES_TIME = 8;

    public static final int PSR_USER_STYLE_NR = 12;
    public static final int PSR_PARENTAL_LVL = 13;
    public static final int PSR_SECONDARY_AUDIO_STN = 14;
    public static final int PSR_PLAYER_CONFIG_AUDIO = 15;
    public static final int PSR_LANG_CODE_AUDIO = 16;
    public static final int PSR_LANG_CODE_PG_TXTST = 17;
    public static final int PSR_MENU_DESCR_LANG_CODE = 18;
    public static final int PSR_COUNTRY_CODE = 19;
    public static final int PSR_REGION_PLAYBACK_CODE = 20;
    public static final int PSR_OUTPUT_MODE_PREFERENCE = 21;
    public static final int PSR_3D_STATUS = 22;
    public static final int PSR_DISPLAY_CAPABILITY = 23;
    public static final int PSR_3D_CAPABILITY = 24;

    public static final int PSR_VIDEO_CAPABILITY = 29;
    public static final int PSR_PLAYER_CAP_TXTST = 30;
    public static final int PSR_PLAYER_PROFILE = 31;
    private static final int TRACE_GPR_BOOT_FLAG = 1889;

    private static final RegisterAccess instance = new RegisterAccess();

    private static final Logger logger = Logger.getLogger(RegisterAccess.class.getName());
}
