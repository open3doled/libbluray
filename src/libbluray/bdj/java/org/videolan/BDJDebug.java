/*
 * This file is part of libbluray
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

package org.videolan;

public class BDJDebug {

    private static final int MAX_CALLER_FRAMES = 8;

    private static boolean isInterestingCaller(String className) {
        return className.startsWith("defpackage.") ||
               className.startsWith("org.havi.ui.");
    }

    private static boolean isInternalCaller(String className) {
        return className.equals(BDJDebug.class.getName()) ||
               className.startsWith("java.lang.Thread") ||
               className.startsWith("java.lang.reflect.") ||
               className.startsWith("jdk.internal.reflect.") ||
               className.startsWith("sun.reflect.") ||
               className.startsWith("org.videolan.") ||
               className.startsWith("java.awt.BD") ||
               className.startsWith("java.awt.peer.BD") ||
               className.startsWith("java.awt.image.BD") ||
               className.startsWith("sun.awt.") ||
               className.startsWith("java.lang.Throwable");
    }

    public static String callerSummary() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        StringBuilder buf = new StringBuilder();
        int added = 0;

        for (int i = 0; i < stack.length; i++) {
            String className = stack[i].getClassName();
            if (!isInterestingCaller(className)) {
                continue;
            }
            if (added == 0) {
                buf.append(" callers=");
            } else {
                buf.append(" <- ");
            }
            buf.append(className)
               .append('#')
               .append(stack[i].getMethodName())
               .append(':')
               .append(stack[i].getLineNumber());
            added++;
            if (added >= MAX_CALLER_FRAMES) {
                return buf.toString();
            }
        }

        for (int i = 0; i < stack.length; i++) {
            String className = stack[i].getClassName();
            if (isInternalCaller(className)) {
                continue;
            }
            if (added == 0) {
                buf.append(" callers=");
            } else {
                buf.append(" <- ");
            }
            buf.append(className)
               .append('#')
               .append(stack[i].getMethodName())
               .append(':')
               .append(stack[i].getLineNumber());
            added++;
            if (added >= MAX_CALLER_FRAMES) {
                break;
            }
        }

        return buf.toString();
    }

    public static boolean callerContains(String className, String methodName) {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (int i = 0; i < stack.length; i++) {
            String stackClassName = stack[i].getClassName();
            if (!stackClassName.equals(className) &&
                    !stackClassName.endsWith("." + className)) {
                continue;
            }
            if (methodName != null && !stack[i].getMethodName().equals(methodName)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        value = value.trim();
        return value.equals("1") ||
               value.equalsIgnoreCase("true") ||
               value.equalsIgnoreCase("yes") ||
               value.equalsIgnoreCase("on");
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static boolean lifecycleEnabled() {
        return TRACE_LIFECYCLE;
    }

    public static boolean sceneEnabled() {
        return TRACE_SCENE;
    }

    public static boolean graphicsEnabled() {
        return TRACE_GRAPHICS;
    }

    public static boolean mediaClockEnabled() {
        return TRACE_MEDIA_CLOCK;
    }

    public static long mediaClockThresholdNs() {
        return TRACE_MEDIA_CLOCK_THRESHOLD_NS;
    }

    public static int tracedGprNum() {
        return TRACE_GPR_NUM;
    }

    public static void traceLifecycle(Logger logger, String message) {
        if (TRACE_LIFECYCLE) {
            logger.error("TRACE lifecycle: " + message);
        }
    }

    public static void traceScene(Logger logger, String message) {
        if (TRACE_SCENE) {
            logger.error("TRACE scene: " + message);
        }
    }

    public static void traceGraphics(Logger logger, String message) {
        if (TRACE_GRAPHICS) {
            logger.error("TRACE graphics: " + message);
        }
    }

    public static void traceMediaClock(Logger logger, String message) {
        if (TRACE_MEDIA_CLOCK) {
            logger.error("TRACE mediaClock: " + message);
        }
    }

    public static void traceRegister(Logger logger, String message) {
        if (TRACE_GPR_NUM >= 0) {
            logger.error("TRACE register: " + message);
        }
    }

    public static String formatEvent(int event) {
        switch (event) {
        case Libbluray.BDJ_EVENT_PLAYLIST:
            return "PLAYLIST";
        case Libbluray.BDJ_EVENT_PLAYITEM:
            return "PLAYITEM";
        case Libbluray.BDJ_EVENT_CHAPTER:
            return "CHAPTER";
        case Libbluray.BDJ_EVENT_MARK:
            return "MARK";
        case Libbluray.BDJ_EVENT_PTS:
            return "PTS";
        case Libbluray.BDJ_EVENT_END_OF_PLAYLIST:
            return "END_OF_PLAYLIST";
        case Libbluray.BDJ_EVENT_SEEK:
            return "SEEK";
        case Libbluray.BDJ_EVENT_RATE:
            return "RATE";
        case Libbluray.BDJ_EVENT_ANGLE:
            return "ANGLE";
        case Libbluray.BDJ_EVENT_AUDIO_STREAM:
            return "AUDIO_STREAM";
        case Libbluray.BDJ_EVENT_SUBTITLE:
            return "SUBTITLE";
        case Libbluray.BDJ_EVENT_SECONDARY_STREAM:
            return "SECONDARY_STREAM";
        case Libbluray.BDJ_EVENT_UO_MASKED:
            return "UO_MASKED";
        default:
            return "EVENT_" + event;
        }
    }

    private static final boolean TRACE_LIFECYCLE =
        isTruthy(System.getenv("LIBBLURAY_BDJ_TRACE_LIFECYCLE")) ||
        isTruthy(System.getProperty("org.videolan.bdj.traceLifecycle"));

    private static final boolean TRACE_SCENE =
        isTruthy(System.getenv("LIBBLURAY_BDJ_TRACE_SCENE")) ||
        isTruthy(System.getProperty("org.videolan.bdj.traceScene"));

    private static final boolean TRACE_GRAPHICS =
        isTruthy(System.getenv("LIBBLURAY_BDJ_TRACE_GRAPHICS")) ||
        isTruthy(System.getProperty("org.videolan.bdj.traceGraphics"));

    private static final boolean TRACE_MEDIA_CLOCK =
        isTruthy(System.getenv("LIBBLURAY_BDJ_TRACE_MEDIA_CLOCK")) ||
        isTruthy(System.getProperty("org.videolan.bdj.traceMediaClock"));

    private static final long TRACE_MEDIA_CLOCK_THRESHOLD_NS =
        parseLong(System.getenv("LIBBLURAY_BDJ_TRACE_MEDIA_CLOCK_THRESHOLD_NS"),
            parseLong(System.getProperty("org.videolan.bdj.traceMediaClockThresholdNs"), -1L));

    private static final int TRACE_GPR_NUM =
        parseInt(System.getenv("LIBBLURAY_BDJ_TRACE_GPR_NUM"),
            parseInt(System.getProperty("org.videolan.bdj.traceGprNum"), -1));
}
