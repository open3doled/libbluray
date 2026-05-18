/*
 * This file is part of libbluray
 * Copyright (C) 2012  Petri Hintukainen <phintuka@users.sourceforge.net>
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

import java.io.PrintStream;

public class Logger {
    static {
        String prop;

        prop = System.getProperty("debug.unimplemented.throw");
        use_throw = (prop != null && prop.equalsIgnoreCase("YES"));

        prop = System.getProperty("debug.trace");
        use_trace = (prop == null || !prop.equalsIgnoreCase("NO"));

        use_open3d_trace =
            isTruthy(System.getenv("OPEN3D_LIBBLURAY_TRACE")) ||
            isTruthy(System.getenv("LIBBLURAY_BDJ_TRACE_LIFECYCLE")) ||
            isTruthy(System.getenv("LIBBLURAY_BDJ_TRACE_SCENE")) ||
            isTruthy(System.getenv("LIBBLURAY_BDJ_TRACE_GRAPHICS")) ||
            isTruthy(System.getenv("LIBBLURAY_BDJ_TRACE_MEDIA_CLOCK")) ||
            isTruthy(System.getenv("LIBBLURAY_BDJ_TRACE_GPR_NUM")) ||
            isTruthy(System.getProperty("org.videolan.bdj.traceLifecycle")) ||
            isTruthy(System.getProperty("org.videolan.bdj.traceScene")) ||
            isTruthy(System.getProperty("org.videolan.bdj.traceGraphics")) ||
            isTruthy(System.getProperty("org.videolan.bdj.traceMediaClock")) ||
            isTruthy(System.getProperty("org.videolan.bdj.traceGprNum"));

        // capture stdout and stderr from on-disc applets
        // (those produce useful debug information sometimes)
        try {
            System.setOut(createCapture(System.out, false));
            System.setErr(createCapture(System.err, true));
        } catch (java.io.UnsupportedEncodingException uee) {
            System.err.println("Error capturing stdout/stderr: " + uee);
        }
    }

    private static class Location {
        public int line = 0;
        public String file = "?";
        public String cls = "?";
        public String func = "?";

        Location() {
        }

        Location(StackTraceElement e) {
            line = e.getLineNumber();
            file = e.getFileName();
            cls = e.getClassName();
            func = e.getMethodName();
            if (file == null) {
                file = "<unknown.java>";
            }
        }
    }

    private static Location getLocation(int depth) {
        StackTraceElement e[] = new Exception("Stack trace").getStackTrace();
        if (e != null && e.length > 1) {
            for (int i = depth; i < e.length; i++)
                if (!e[i].getClassName().startsWith("org.videolan.Logger"))
                    return new Location(e[i]);
        }
        return new Location();
    }

    private static PrintStream createCapture(final PrintStream printStream, final boolean error)
        throws java.io.UnsupportedEncodingException {

        return new PrintStream(printStream, false, "UTF-8") {
            public void print(final String string) {
                Logger.log(error, string);
            }

            public void println(final String string) {
                Logger.log(error, string);
            }
        };
    }

    public static Logger getLogger(String name) {
        return new Logger(name);
    }

    private Logger(String name) {
        this.name = name;
    }

    private static native void logN(boolean error, String file, int line, String msg);

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

    private static boolean shouldSuppressOpen3DTrace(String msg) {
        if (use_open3d_trace || msg == null) {
            return false;
        }
        return msg.startsWith("TRACE ") ||
               msg.startsWith("INFO: TRACE ") ||
               msg.startsWith("ERROR: TRACE ") ||
               msg.startsWith("Ixc TRACE: ") ||
               msg.startsWith("open3d_libbluray_mvc_diag:");
    }

    private static void log(boolean error, String cls, String msg) {
        if (shouldSuppressOpen3DTrace(msg)) {
            return;
        }
        logN(error, cls, 0, msg);
    }

    private static void log(boolean error, String msg) {
        if (shouldSuppressOpen3DTrace(msg)) {
            return;
        }
        Location l = getLocation(3);
        logN(error, l.file + ":" + l.cls + "." + l.func, l.line, msg);
    }

    public void trace(String msg) {
        if (use_trace) {
            log(false, name, msg);
        }
    }

    public void info(String msg) {
        log(false, name, "INFO: " + msg);
    }

    public void warning(String msg) {
        log(false, name, "WARNING: " + msg);
    }

    public void error(String msg) {
        log(true, name, "ERROR: " + msg);
    }

    public void unimplemented() {
        unimplemented(null);
    }

    private static String printStackTrace(StackTraceElement[] e, int offset) {
        if (e != null && e.length > 1) {
            StringBuffer dump = new StringBuffer();
            dump.append("\t");
            dump.append(e[offset].toString());
            for (int i = offset + 1; i < e.length; i++) {
                dump.append("\n\t");
                dump.append(e[i].toString());
            }
            return dump.toString();
        }
        return "";
    }

    public static String dumpStack() {
        StackTraceElement e[] = new Exception("Stack trace").getStackTrace();
        return printStackTrace(e, 2);
    }

    public static String dumpStack(Throwable t) {
        String sup = "";
        if (t.getCause() != null)
            sup += "\n    Caused by: " + t.getCause() + "\n" + dumpStack(t.getCause());
        StackTraceElement e[] = t.getStackTrace();
        return printStackTrace(e, 0) + sup;
    }

    public void unimplemented(String func) {
        String location = name;
        if (func != null) {
            location = location + "." + func + "()";
        }

        log(true, "UNIMPLEMENTED: " + location + "\n" + dumpStack());

        if (use_throw) {
            throw new Error("Not implemented: " + location);
        }
    }

    public static void unimplemented(String cls, String func) {
        if (cls == null)
            cls = "<?>";

        if (func == null)
            func = "";
        else
            func = "." + func + "()";

        String location = cls + func;

        log(true, "UNIMPLEMENTED: " + location + "\n" + dumpStack());

        if (use_throw) {
            throw new Error("Not implemented: " + location);
        }
    }

    private final String name;
    private static final boolean use_trace;
    private static final boolean use_throw;
    private static final boolean use_open3d_trace;
}
