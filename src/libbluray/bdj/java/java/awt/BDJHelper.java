 /*
 * This file is part of libbluray
 * Copyright (C) 2012  Libbluray
 * Copyright (C) 2013  Petri Hintukainen <phintuka@users.sourceforge.net>
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

package java.awt;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.awt.event.InvocationEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.peer.BDKeyboardFocusManagerPeer;

import org.bluray.system.RegisterAccess;
import org.videolan.BDJDebug;
import org.videolan.BDJXletContext;
import org.videolan.Libbluray;
import org.videolan.Logger;

public class BDJHelper {
    private static final boolean TRACE_ACTIVE_CONTROLLER =
        traceFlagEnabled("LIBBLURAY_BDJ_TRACE_ACTIVE_CONTROLLER",
                         "org.videolan.bdj.traceActiveController");

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

    private static boolean traceFlagEnabled(String envName, String propertyName) {
        try {
            if (System.getSecurityManager() == null &&
                isTruthy(System.getenv(envName))) {
                return true;
            }
        } catch (Throwable t) {
        }

        try {
            if (isTruthy(System.getProperty(propertyName))) {
                return true;
            }
        } catch (Throwable t) {
        }

        return false;
    }

    public static EventDispatchThread getEventDispatchThread(EventQueue eq) {
        if (eq != null) {
            return eq.getDispatchThread();
        }
        return null;
    }

    public static void stopEventQueue(EventQueue eq) {
        EventDispatchThread t = eq.getDispatchThread();
        if (t != null && t.isAlive()) {

            final long DISPOSAL_TIMEOUT = 5000;
            final Object notificationLock = new Object();
            Runnable runnable = new Runnable() { public void run() {
                synchronized(notificationLock) {
                    notificationLock.notifyAll();
                }
            } };

            synchronized (notificationLock) {
                eq.postEvent(new InvocationEvent(Toolkit.getDefaultToolkit(), runnable));
                try {
                    notificationLock.wait(DISPOSAL_TIMEOUT);
                } catch (InterruptedException e) {
                }
            }

            t.stopDispatching();
            if (t.isAlive()) {
                t.interrupt();
            }

            try {
                t.join(1000);
            } catch (InterruptedException e) {
            }
            if (t.isAlive()) {
                logger.error("stopEventQueue() failed for " + t);
                org.videolan.PortingHelper.stopThread(t);
            }
        }
    }

    /*
     * Mouse events
     */

    private static int mouseX = 0, mouseY = 0, mouseMask = 0;

    public static boolean postMouseEvent(int x, int y) {
        mouseX = x;
        mouseY = y;
        return postMouseEventImpl(MouseEvent.MOUSE_MOVED, MouseEvent.NOBUTTON);
    }

    public static boolean postMouseEvent(int id) {
        boolean r;

        if (id == MouseEvent.MOUSE_PRESSED)
            mouseMask = MouseEvent.BUTTON1_MASK;

        r = postMouseEventImpl(id, MouseEvent.BUTTON1);

        if (id == MouseEvent.MOUSE_RELEASED)
            mouseMask = 0;

        return r;
    }

    private static boolean postMouseEventImpl(int id, int button) {
        Component focusOwner = getInputTarget();
        if (focusOwner != null) {
            EventQueue eq = BDToolkit.getEventQueue(focusOwner);
            if (eq != null) {
                long when = System.currentTimeMillis();
                try {
                    eq.postEvent(new MouseEvent(focusOwner, id, when, mouseMask, mouseX, mouseY,
                                                (id == MouseEvent.MOUSE_CLICKED) ? 1 : 0, false, button));
                    return true;
                } catch (Exception e) {
                    logger.error("postMouseEvent failed: " + e);
                }
            }
        }
        return false;
    }

    /*
     * Key events
     */

    public static boolean postKeyEvent(int id, int modifiers, int keyCode) {
        Component focusOwner = getInputTarget();
        BDJDebug.traceScene(logger,
                            "postKeyEvent id=" + id +
                            " keyCode=" + keyCode +
                            " focusOwner=" + focusOwner);
        traceActiveController(id, keyCode, focusOwner);
        if (focusOwner != null) {
            long when = System.currentTimeMillis();
            KeyEvent event;
            try {
                if (id == KeyEvent.KEY_TYPED)
                    event = new KeyEvent(focusOwner, id, when, modifiers, KeyEvent.VK_UNDEFINED, (char)keyCode);
                else
                    event = new KeyEvent(focusOwner, id, when, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED);

                EventQueue eq = BDToolkit.getEventQueue(focusOwner);
                if (eq != null) {
                    eq.postEvent(event);
                    return true;
                }
            } catch (Exception e) {
                logger.error("postKeyEvent failed: " + e);
            }
        } else {
            logger.error("KEY event dropped (no focus owner)");
        }

        return false;
    }

    private static void traceActiveController(int id, int keyCode, Component focusOwner) {
        if (!TRACE_ACTIVE_CONTROLLER && !BDJDebug.lifecycleEnabled()) {
            return;
        }

        BDJXletContext focusContext = BDJXletContext.getFocusContext();
        BDJXletContext currentContext = BDJXletContext.getCurrentContext();

        traceActiveControllerLoader("focusContext",
                                    focusContext != null ? focusContext.getClassLoader() : null,
                                    id, keyCode, focusOwner);
        traceActiveControllerLoader("currentContext",
                                    currentContext != null ? currentContext.getClassLoader() : null,
                                    id, keyCode, focusOwner);
        traceActiveControllerLoader("threadContext",
                                    Thread.currentThread().getContextClassLoader(),
                                    id, keyCode, focusOwner);
        traceActiveControllerLoader("focusOwner",
                                    focusOwner != null ? focusOwner.getClass().getClassLoader() : null,
                                    id, keyCode, focusOwner);

        traceFocusOwnerKeyListeners(id, keyCode, focusOwner);
    }

    private static void traceActiveControllerLoader(String source, ClassLoader loader,
                                                    int id, int keyCode, Component focusOwner) {
        if (loader == null) {
            logger.error("TRACE activeController source=" + source +
                         " id=" + id +
                         " keyCode=" + keyCode +
                         " focusOwner=" + focusOwner +
                         " loader=null");
            return;
        }

        try {
            Class iiClass = Class.forName("ii", false, loader);
            Method tMethod = iiClass.getMethod("t");
            Object controller = tMethod.invoke(null);
            Object rootController = invokeStaticNoArg(loader, "ii", "u");
            Object popupController = invokeStaticNoArg(loader, "ii", "v");
            String controllerClass = (controller != null) ? controller.getClass().getName() : "null";
            String title = lookupIntStatic(loader, "fl", "f");
            String controllerState = lookupField(controller, "n");
            String controllerIndex = lookupField(controller, "i");
            Object selected = invokeNoArg(controller, "s");
            String selectedClass = (selected != null) ? selected.getClass().getName() : "null";
            String selectedChildClass = lookupFieldClass(selected, "J");
            Object forwardedChild = lookupFieldObject(controller, "o");
            String forwardedChildClass = (forwardedChild != null) ?
                forwardedChild.getClass().getName() : "null";
            String forwardedChildState = lookupField(forwardedChild, "n");
            String forwardedChildIndex = lookupField(forwardedChild, "i");
            Object forwardedSelected = invokeNoArg(forwardedChild, "s");
            String forwardedSelectedClass = (forwardedSelected != null) ?
                forwardedSelected.getClass().getName() : "null";
            String forwardedSelectedChildClass = lookupFieldClass(forwardedSelected, "J");
            boolean traceDiscState =
                "bl".equals(controllerClass) ||
                "bl".equals(selectedClass) ||
                "bl".equals(selectedChildClass) ||
                "bl".equals(forwardedChildClass) ||
                "bl".equals(forwardedSelectedClass) ||
                "bl".equals(forwardedSelectedChildClass) ||
                "ml".equals(controllerClass) ||
                "ml".equals(selectedClass) ||
                "ml".equals(selectedChildClass) ||
                "ml".equals(forwardedChildClass) ||
                "ml".equals(forwardedSelectedClass) ||
                "ml".equals(forwardedSelectedChildClass);
            String discState = traceDiscState ? describeDiscStateForLoader(loader) : "";
            logger.error("TRACE activeController source=" + source +
                         " id=" + id +
                         " keyCode=" + keyCode +
                         " title=" + title +
                         " controller=" + controllerClass +
                         " iiT=" + describeObject(controller) +
                         " iiU=" + describeObject(rootController) +
                         " iiV=" + describeObject(popupController) +
                         " iiTisU=" + String.valueOf(controller == rootController) +
                         " iiTisV=" + String.valueOf(controller == popupController) +
                         " state=" + controllerState +
                         " index=" + controllerIndex +
                         " selected=" + selectedClass +
                         " selectedChild=" + selectedChildClass +
                         " forwardChild=" + forwardedChildClass +
                         " forwardState=" + forwardedChildState +
                         " forwardIndex=" + forwardedChildIndex +
                         " forwardSelected=" + forwardedSelectedClass +
                         " forwardSelectedChild=" + forwardedSelectedChildClass +
                         discState +
                         " focusOwner=" + focusOwner +
                         " loader=" + describeLoader(loader));
        } catch (Throwable t) {
            logger.error("TRACE activeController source=" + source +
                         " id=" + id +
                         " keyCode=" + keyCode +
                         " focusOwner=" + focusOwner +
                         " loader=" + describeLoader(loader) +
                         " error=" + t);
        }
    }

    private static void traceFocusOwnerKeyListeners(int id, int keyCode, Component focusOwner) {
        if (focusOwner == null) {
            return;
        }

        Component current = focusOwner;
        int depth = 0;
        while (current != null) {
            KeyListener[] listeners = current.getKeyListeners();
            if (listeners != null && listeners.length > 0) {
                StringBuffer details = new StringBuffer();
                for (int i = 0; i < listeners.length; i++) {
                    if (i > 0) {
                        details.append(", ");
                    }
                    details.append(listeners[i].getClass().getName());
                    details.append(" loader=");
                    details.append(describeLoader(listeners[i].getClass().getClassLoader()));
                }
                logger.error("TRACE activeController listeners id=" + id +
                             " keyCode=" + keyCode +
                             " depth=" + depth +
                             " component=" + current.getClass().getName() +
                             " componentLoader=" + describeLoader(current.getClass().getClassLoader()) +
                             " listeners=" + details.toString());
            }
            current = current.getParent();
            depth++;
        }
    }

    private static String lookupIntStatic(ClassLoader loader, String className, String methodName) {
        try {
            Class cls = Class.forName(className, false, loader);
            Method method = cls.getMethod(methodName);
            return String.valueOf(method.invoke(null));
        } catch (Throwable t) {
            return "error:" + t.getClass().getSimpleName();
        }
    }

    private static String lookupBooleanStatic(ClassLoader loader, String className, String methodName) {
        try {
            Class cls = Class.forName(className, false, loader);
            Method method = cls.getMethod(methodName);
            return String.valueOf(method.invoke(null));
        } catch (Throwable t) {
            return "error:" + t.getClass().getSimpleName();
        }
    }

    private static Object invokeStaticNoArg(ClassLoader loader, String className, String methodName) {
        try {
            Class cls = Class.forName(className, false, loader);
            Method method = cls.getMethod(methodName);
            return method.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String lookupIntStaticArg(ClassLoader loader, String className,
                                             String methodName, int value) {
        try {
            Class cls = Class.forName(className, false, loader);
            Method method = cls.getMethod(methodName, new Class[] { Integer.TYPE });
            return String.valueOf(method.invoke(null, new Object[] { Integer.valueOf(value) }));
        } catch (Throwable t) {
            return "error:" + t.getClass().getSimpleName();
        }
    }

    private static String lookupField(Object target, String fieldName) {
        if (target == null) {
            return "null";
        }
        try {
            Object value = readField(target, fieldName);
            if (value == null) {
                return "null";
            }
            return String.valueOf(value);
        } catch (Throwable t) {
            return "error:" + t.getClass().getSimpleName();
        }
    }

    private static String lookupFieldClass(Object target, String fieldName) {
        if (target == null) {
            return "null";
        }
        try {
            Object value = readField(target, fieldName);
            return (value != null) ? value.getClass().getName() : "null";
        } catch (Throwable t) {
            return "error:" + t.getClass().getSimpleName();
        }
    }

    private static Object lookupFieldObject(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        try {
            return readField(target, fieldName);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object readField(Object target, String fieldName) throws IllegalAccessException {
        java.lang.reflect.Field field = findField(target.getClass(), fieldName);
        if (field == null) {
            return null;
        }
        if (!Modifier.isPublic(field.getModifiers()) ||
            !Modifier.isPublic(field.getDeclaringClass().getModifiers())) {
            field.setAccessible(true);
        }
        return field.get(target);
    }

    private static java.lang.reflect.Field findField(Class cls, String fieldName) {
        try {
            return cls.getField(fieldName);
        } catch (NoSuchFieldException e) {
        }
        Class current = cls;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static String describeLoader(ClassLoader loader) {
        if (loader == null) {
            return "null";
        }
        return loader.getClass().getName() + "@" +
               Integer.toHexString(System.identityHashCode(loader));
    }

    private static String lookupGlobalGpr(int value) {
        try {
            return String.valueOf(Libbluray.readGPR(value));
        } catch (Throwable t) {
            return "error:" + t.getClass().getSimpleName();
        }
    }

    private static String lookupGlobalPsr(int value) {
        try {
            return String.valueOf(Libbluray.readPSR(value));
        } catch (Throwable t) {
            return "error:" + t.getClass().getSimpleName();
        }
    }

    private static String describeStartupPsrs(ClassLoader loader) {
        if (loader == null) {
            return " psr1=" + lookupGlobalPsr(RegisterAccess.PSR_AUDIO_STN) +
                   " psr2=" + lookupGlobalPsr(RegisterAccess.PSR_PG_TXTST_STN) +
                   " psr16=" + lookupGlobalPsr(RegisterAccess.PSR_LANG_CODE_AUDIO) +
                   " psr17=" + lookupGlobalPsr(RegisterAccess.PSR_LANG_CODE_PG_TXTST) +
                   " psr18=" + lookupGlobalPsr(RegisterAccess.PSR_MENU_DESCR_LANG_CODE) +
                   " psr19=" + lookupGlobalPsr(RegisterAccess.PSR_COUNTRY_CODE) +
                   " psr20=" + lookupGlobalPsr(RegisterAccess.PSR_REGION_PLAYBACK_CODE);
        }

        return " psr1=" + lookupIntStaticArg(loader, "org.videolan.Libbluray", "readPSR",
                                             RegisterAccess.PSR_AUDIO_STN) +
               " psr2=" + lookupIntStaticArg(loader, "org.videolan.Libbluray", "readPSR",
                                             RegisterAccess.PSR_PG_TXTST_STN) +
               " psr16=" + lookupIntStaticArg(loader, "org.videolan.Libbluray", "readPSR",
                                              RegisterAccess.PSR_LANG_CODE_AUDIO) +
               " psr17=" + lookupIntStaticArg(loader, "org.videolan.Libbluray", "readPSR",
                                              RegisterAccess.PSR_LANG_CODE_PG_TXTST) +
               " psr18=" + lookupIntStaticArg(loader, "org.videolan.Libbluray", "readPSR",
                                              RegisterAccess.PSR_MENU_DESCR_LANG_CODE) +
               " psr19=" + lookupIntStaticArg(loader, "org.videolan.Libbluray", "readPSR",
                                              RegisterAccess.PSR_COUNTRY_CODE) +
               " psr20=" + lookupIntStaticArg(loader, "org.videolan.Libbluray", "readPSR",
                                              RegisterAccess.PSR_REGION_PLAYBACK_CODE);
    }

    private static String describeObject(Object object) {
        if (object == null) {
            return "null";
        }
        return object.getClass().getName() + "@" +
               Integer.toHexString(System.identityHashCode(object));
    }

    public static String describeDiscState() {
        BDJXletContext currentContext = null;
        try {
            currentContext = BDJXletContext.getCurrentContext();
        } catch (Throwable t) {
        }
        if (currentContext != null) {
            return describeDiscStateForLoader("currentContext",
                                              currentContext.getClassLoader());
        }

        BDJXletContext focusContext = null;
        try {
            focusContext = BDJXletContext.getFocusContext();
        } catch (Throwable t) {
        }
        if (focusContext != null) {
            return describeDiscStateForLoader("focusContext",
                                              focusContext.getClassLoader());
        }

        return describeDiscStateForLoader("threadContext",
                                          Thread.currentThread().getContextClassLoader());
    }

    public static String describeDiscStateForLoader(ClassLoader loader) {
        return describeDiscStateForLoader(null, loader);
    }

    private static String describeNativeVfsGuard() {
        try {
            return " " + Libbluray.describeVirtualPackageGuard();
        } catch (Throwable t) {
            return " nativeCanSetVp=error";
        }
    }

    private static String describeDiscStateForLoader(String source, ClassLoader loader) {
        String prefix = (source != null) ? " discStateSource=" + source : "";
        if (loader == null) {
            return prefix + " discStateLoader=null" +
                   " gpr125=" + lookupGlobalGpr(125) +
                   " gpr550=" + lookupGlobalGpr(550) +
                   " gpr120=" + lookupGlobalGpr(120) +
                   " gpr503=" + lookupGlobalGpr(503) +
                   describeStartupPsrs(null) +
                   describeNativeVfsGuard();
        }

        return prefix +
               " discStateLoader=" + describeLoader(loader) +
               " gpr125=" + lookupIntStaticArg(loader, "org.videolan.Libbluray", "readGPR", 125) +
               " gpr550=" + lookupIntStaticArg(loader, "org.videolan.Libbluray", "readGPR", 550) +
               " gpr120=" + lookupIntStaticArg(loader, "org.videolan.Libbluray", "readGPR", 120) +
               " gpr503=" + lookupIntStaticArg(loader, "org.videolan.Libbluray", "readGPR", 503) +
               describeStartupPsrs(loader) +
               describeNativeVfsGuard();
    }

    private static Component getInputTarget() {
        KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        Component globalFocusOwner = kfm.getGlobalFocusOwner();
        BDKeyboardFocusManagerPeer peer =
            (BDKeyboardFocusManagerPeer)BDKeyboardFocusManagerPeer.getInstance();
        Component peerFocusOwner = peer.getCurrentFocusOwner();
        Window peerWindow = peer.getCurrentFocusedWindow();

        Component target = globalFocusOwner;
        if ((target == null || target instanceof Window) && peerFocusOwner != null) {
            target = peerFocusOwner;
        } else if (target == null && peerWindow != null) {
            target = peerWindow;
        }

        BDJDebug.traceScene(logger,
                            "getInputTarget global=" + globalFocusOwner +
                            " peer=" + peerFocusOwner +
                            " peerWindow=" + peerWindow +
                            " chosen=" + target);
        return target;
    }

    private static final Logger logger = Logger.getLogger(BDJHelper.class.getName());
}
