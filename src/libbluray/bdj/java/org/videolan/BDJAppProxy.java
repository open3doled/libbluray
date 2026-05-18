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

import org.dvb.application.AppID;
import org.dvb.application.AppStateChangeEvent;
import org.dvb.application.AppStateChangeEventListener;
import org.dvb.application.DVBJProxy;

import java.awt.EventQueue;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import javax.tv.xlet.Xlet;

class BDJAppProxy implements DVBJProxy, Runnable {
    protected static BDJAppProxy newInstance(BDJXletContext context) {
        BDJAppProxy proxy = new BDJAppProxy(context);
        /* do not create and start thread in constructor.
           if constructor fails (exception), thread is left running without BDJAppProxy ... */
        proxy.startThread();
        return proxy;
    }

    private void startThread() {
        thread = new Thread(context.getThreadGroup(), this, "BDJAppProxy");
        thread.setDaemon(true);
        thread.start();

        /* wait until thread has been started and event queue is initialized.
         * We want event dispatcher thread to be inside xlet thread group
         * -> event queue must be created from thread running inside applet thread group.
         */
        while (context.getEventQueue() == null) {
            Thread.yield();
        }
    }

    private BDJAppProxy(BDJXletContext context) {
        this.context = context;
        state = NOT_LOADED;
    }

    public int getState() {
        return state;
    }

    public void load() {
        AppCommand cmd = new AppCommand(AppCommand.CMD_LOAD, null);
        synchronized (cmds) {
            cmds.addLast(cmd);
            cmds.notifyAll();
        }
    }

    public void init() {
        AppCommand cmd = new AppCommand(AppCommand.CMD_INIT, null);
        synchronized (cmds) {
            cmds.addLast(cmd);
            cmds.notifyAll();
        }
    }

    public void start() {
        start(null);
    }

    public void start(String[] args) {
        AppCommand cmd = new AppCommand(AppCommand.CMD_START, args);
        synchronized (cmds) {
            cmds.addLast(cmd);
            cmds.notifyAll();
        }
    }

    public void stop(boolean force, int timeout) {
        AppCommand cmd = new AppCommand(AppCommand.CMD_STOP, Boolean.valueOf(force));
        synchronized (cmds) {
            cmds.addLast(cmd);
            cmds.notifyAll();
        }
        if (timeout > 0) {
            if (!cmd.waitDone(timeout)) {
                logger.error("stop() timeout: Xlet " + context.getThreadGroup().getName());
            }
        }
    }

    public void stop(boolean force) {
        stop(force, -1);
    }

    public void pause() {
        AppCommand cmd = new AppCommand(AppCommand.CMD_PAUSE, null);
        synchronized (cmds) {
            cmds.addLast(cmd);
            cmds.notifyAll();
        }
    }

    public void resume() {
        AppCommand cmd = new AppCommand(AppCommand.CMD_RESUME, null);
        synchronized (cmds) {
            cmds.addLast(cmd);
            cmds.notifyAll();
        }
    }

    protected void notifyDestroyed() {
        AppCommand cmd = new AppCommand(AppCommand.CMD_NOTIFY_DESTROYED, null);
        synchronized (cmds) {
            cmds.addLast(cmd);
            cmds.notifyAll();
        }
    }

    protected void notifyPaused() {
        AppCommand cmd = new AppCommand(AppCommand.CMD_NOTIFY_PAUSED, null);
        synchronized (cmds) {
            cmds.addLast(cmd);
            cmds.notifyAll();
        }
    }

    protected void release() {
        BDJDebug.traceLifecycle(logger, "proxy release begin context=" + context + " cleanup=" + context.cleanupState());
        AppCommand cmd = new AppCommand(AppCommand.CMD_STOP, Boolean.valueOf(true));
        synchronized (cmds) {
            cmds.addLast(cmd);
            cmds.addLast(null);
            cmds.notifyAll();
        }

        if (!cmd.waitDone(5000)) {
            logger.error("release(): STOP timeout, killing Xlet " + context.getThreadGroup().getName());
        }

        BDJDebug.traceLifecycle(logger, "proxy release beforeContextRelease context=" + context + " cleanup=" + context.cleanupState());
        context.release();
        BDJDebug.traceLifecycle(logger, "proxy release afterContextRelease context=" + context + " cleanup=" + context.cleanupState());
    }

    public void addAppStateChangeEventListener(AppStateChangeEventListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    public void removeAppStateChangeEventListener(AppStateChangeEventListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners(int fromState, int toState, boolean hasFailed) {
        LinkedList list;
        synchronized (listeners) {
            list = (LinkedList)listeners.clone();
        }

        AppStateChangeEvent event = new AppStateChangeEvent(
                (AppID)context.getXletProperty("org.dvb.application.appid"),
                fromState, toState, this, hasFailed);
        for (int i = 0; i < list.size(); i++)
            ((AppStateChangeEventListener)list.get(i)).stateChange(event);
    }

    protected BDJXletContext getXletContext() {
        return context;
    }

    private void createStorage()
    {
        final String persistentOrg = System.getProperty("dvb.persistent.root") + File.separator +
            (String)context.getXletProperty("dvb.org.id") + File.separator;
        final String persistentApp = persistentOrg + (String)context.getXletProperty("dvb.app.id");
        File f = new File(persistentApp);
        if (!f.isDirectory() && !f.mkdirs()) {
            logger.error("Error creating persistent storage " + persistentApp);
        }

        final String budaOrg = System.getProperty("bluray.bindingunit.root") + File.separator +
            (String)context.getXletProperty("dvb.org.id") + File.separator;
        final String budaDisc = budaOrg + org.bluray.ti.DiscManager.getDiscManager().getCurrentDisc().getId();
        File fb = new File(budaDisc);
        if (!fb.isDirectory() && !fb.mkdirs()) {
            logger.error("Error creating BUDA storage " + budaDisc);
        }

        synchronized (cleanupMapLock) {
            cleanupMap.put(persistentApp,
                           new Runnable() {
                               public void run() {
                                   if (new File(persistentApp).delete()) {
                                       logger.info("Removed empty " + persistentApp);
                                       if (new File(persistentOrg).delete()) {
                                           logger.info("Removed empty " + persistentOrg);
                                       }
                                   }
                               }
                           });
            cleanupMap.put(budaDisc,
                           new Runnable() {
                               public void run() {
                                   if (new File(budaDisc).delete()) {
                                       logger.info("Removed empty " + budaDisc);
                                       if (new File(budaOrg).delete()) {
                                           logger.info("Removed empty " + budaOrg);
                                       }
                                   }
                               }
                           });
        }
    }

    private boolean doLoad() {
        if (state == NOT_LOADED) {
            try {
                BDJDebug.traceLifecycle(logger, "proxy doLoad context=" + context);
                xlet = ((BDJClassLoader)context.getClassLoader()).loadXlet();
                state = LOADED;
                BDJDebug.traceLifecycle(logger, "proxy doLoad success context=" + context + " xlet=" + xlet.getClass().getName());
                return true;
            } catch (Throwable e) {
                logger.error("doLoad() failed: " + e + "\n" + Logger.dumpStack(e));
                state = INVALID;
            }
        }
        return false;
    }

    private boolean doInit() {
        if ((state == NOT_LOADED) && !doLoad())
            return false;
        if (state == LOADED) {
            try {
                BDJDebug.traceLifecycle(logger, "proxy doInit context=" + context);
                createStorage();

                xlet.initXlet(context);
                state = PAUSED;
                BDJDebug.traceLifecycle(logger, "proxy doInit success context=" + context);
                return true;
            } catch (Throwable e) {
                logger.error("doInit() failed: " + e + "\n" + Logger.dumpStack(e));
                state = INVALID;
            }
        }
        return false;
    }

    private boolean doStart(String[] args) {
        if (((state == NOT_LOADED) || (state == LOADED)) && !doInit())
            return false;
        if (state == PAUSED) {
            try {
                BDJDebug.traceLifecycle(logger, "proxy doStart context=" + context + " args=" + (args == null ? 0 : args.length));
                if (args != null)
                    context.setArgs(args);
                xlet.startXlet();
                state = STARTED;
                BDJDebug.traceLifecycle(logger, "proxy doStart success context=" + context);
                return true;
            } catch (Throwable e) {
                logger.error("doStart() failed: " + e + "\n" + Logger.dumpStack(e));
                state = INVALID;
            }
        }
        return false;
    }

    private String describeStopState(boolean force) {
        BDJThreadGroup threadGroup = context.getThreadGroup();
        String xletState = (xlet == null)
            ? "<null>"
            : xlet.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(xlet));

        return "context=" + context +
               " force=" + force +
               " state=" + state +
               " xlet=" + xletState +
               " threads=" + (threadGroup == null ? -1 : threadGroup.activeCount()) +
               " cleanup=" + context.cleanupState() +
               " discStateSource=proxyContext" +
               java.awt.BDJHelper.describeDiscStateForLoader(context.getClassLoader());
    }

    private void traceStopStep(String step, boolean force) {
        BDJDebug.traceLifecycle(logger, "proxy doStop step=" + step + " " + describeStopState(force));
    }

    private boolean failStopStep(String step, boolean force, Throwable e) {
        BDJDebug.traceLifecycle(logger, "proxy doStop failed step=" + step + " " + describeStopState(force));
        logger.error("doStop(" + step + ") failed: " + e + "\n" + Logger.dumpStack(e));
        if (!force) {
            state = INVALID;
            return false;
        }
        BDJDebug.traceLifecycle(logger, "proxy doStop continuing after failed step=" + step + " because force=true " + describeStopState(force));
        return true;
    }

    private void noteStopFailure(String step, String[] firstFailedStep) {
        if (firstFailedStep[0] == null) {
            firstFailedStep[0] = step;
        }
    }

    private boolean doStop(boolean force) {
        String[] firstFailedStep = new String[1];

        if (state == INVALID)
            return false;
        if ((state != NOT_LOADED) && (state != LOADED)) {
            traceStopStep("begin", force);
            try {
                traceStopStep("beforeDestroy", force);
                xlet.destroyXlet(force);
                traceStopStep("afterDestroy", force);
            } catch (Throwable e) {
                if (!failStopStep("destroyXlet", force, e))
                    return false;
                noteStopFailure("destroyXlet", firstFailedStep);
            }

            try {
                traceStopStep("beforeCloseSockets", force);
                context.closeSockets();
                traceStopStep("afterCloseSockets", force);
            } catch (Throwable e) {
                if (!failStopStep("closeSockets", force, e))
                    return false;
                noteStopFailure("closeSockets", firstFailedStep);
            }

            int shutdownThreadAllowance = 1 + context.numEventQueueThreads();
            try {
                traceStopStep("beforeWaitForShutdown waitMs=1000 extraThreads=" + shutdownThreadAllowance, force);
                context.getThreadGroup().waitForShutdown(1000, shutdownThreadAllowance);
                traceStopStep("afterWaitForShutdown waitMs=1000 extraThreads=" + shutdownThreadAllowance, force);
            } catch (Throwable e) {
                if (!failStopStep("waitForShutdown", force, e))
                    return false;
                noteStopFailure("waitForShutdown", firstFailedStep);
            }

            try {
                traceStopStep("beforeExitXlet", force);
                context.exitXlet();
            } catch (Throwable e) {
                if (!failStopStep("exitXlet", force, e))
                    return false;
                noteStopFailure("exitXlet", firstFailedStep);
            }
            traceStopStep("afterExitXlet", force);
        }
        xlet = null;
        state = DESTROYED;
        if (firstFailedStep[0] != null) {
            traceStopStep("completedWithFailure firstFailedStep=" + firstFailedStep[0], force);
            return false;
        }
        traceStopStep("success", force);
        return true;
    }

    private boolean doPause() {
        if (state == STARTED) {
            try {
                xlet.pauseXlet();
                state = PAUSED;
                return true;
            } catch (Throwable e) {
                logger.error("doPause() failed: " + e + "\n" + Logger.dumpStack(e));
                state = INVALID;
            }
        }
        return false;
    }

    private boolean doResume() {
        if (state == PAUSED) {
            try {
                xlet.startXlet();
                state = STARTED;
                return true;
            } catch (Throwable e) {
                logger.error("doResume() failed: " + e + "\n" + Logger.dumpStack(e));
                state = INVALID;
            }
        }
        return false;
    }

    public void run() {
        if (context.getEventQueue() == null)
            context.setEventQueue(new EventQueue());

        for (;;) {
            AppCommand cmd;
            synchronized (cmds) {
                while (cmds.isEmpty()) {
                    try {
                        cmds.wait();
                    } catch (InterruptedException e) {

                    }
                }
                cmd = (AppCommand)cmds.removeFirst();
            }
            if (cmd == null)
                return;
            int fromState = state;
            int toState;
            boolean ret;
            switch (cmd.getCommand()) {
            case AppCommand.CMD_LOAD:
                toState = LOADED;
                BDJDebug.traceLifecycle(logger, "proxy command LOAD from=" + fromState + " context=" + context);
                ret = doLoad();
                break;
            case AppCommand.CMD_INIT:
                toState = PAUSED;
                BDJDebug.traceLifecycle(logger, "proxy command INIT from=" + fromState + " context=" + context);
                ret = doInit();
                break;
            case AppCommand.CMD_START:
                toState = STARTED;
                BDJDebug.traceLifecycle(logger, "proxy command START from=" + fromState + " context=" + context);
                Object args = cmd.getArgument();
                ret = doStart(args == null ? null : (String[])args);
                break;
            case AppCommand.CMD_STOP:
                toState = DESTROYED;
                BDJDebug.traceLifecycle(logger, "proxy command STOP from=" + fromState + " context=" + context);
                ret = doStop(((Boolean)cmd.getArgument()).booleanValue());
                break;
            case AppCommand.CMD_PAUSE:
                toState = PAUSED;
                BDJDebug.traceLifecycle(logger, "proxy command PAUSE from=" + fromState + " context=" + context);
                ret = doPause();
                break;
            case AppCommand.CMD_RESUME:
                toState = STARTED;
                BDJDebug.traceLifecycle(logger, "proxy command RESUME from=" + fromState + " context=" + context);
                ret = doResume();
                break;
            case AppCommand.CMD_NOTIFY_DESTROYED:
                toState = DESTROYED;
                BDJDebug.traceLifecycle(logger, "proxy command NOTIFY_DESTROYED from=" + fromState + " context=" + context);
                state = DESTROYED;
                ret = true;
                break;
            case AppCommand.CMD_NOTIFY_PAUSED:
                toState = PAUSED;
                BDJDebug.traceLifecycle(logger, "proxy command NOTIFY_PAUSED from=" + fromState + " context=" + context);
                state = PAUSED;
                ret = true;
                break;
            default:
                return;
            }
            BDJDebug.traceLifecycle(logger, "proxy command done from=" + fromState + " to=" + toState + " ret=" + ret + " state=" + state + " context=" + context);
            notifyListeners(fromState, toState, !ret);
            cmd.release();
            if (state == DESTROYED)
                state = NOT_LOADED;
        }
    }

    private BDJXletContext context;
    private Xlet xlet;
    private int state;
    private LinkedList listeners = new LinkedList();
    private LinkedList cmds = new LinkedList();
    private Thread thread;
    private static final Logger logger = Logger.getLogger(BDJAppProxy.class.getName());

    private static Map cleanupMap = new HashMap();
    private static Object cleanupMapLock = new Object();

    protected static void cleanup() {
        Object[] arr;
        synchronized (cleanupMapLock) {
            arr = cleanupMap.values().toArray();
            cleanupMap = new HashMap();
        }
        for (int i = 0; i < arr.length; i++)
            ((Runnable)arr[i]).run();
    }

    private static class AppCommand {
        public AppCommand(int cmd, Object arg) {
            this.cmd = cmd;
            this.arg = arg;
        }

        public int getCommand() {
            return cmd;
        }

        public Object getArgument() {
            return arg;
        }

        public boolean waitDone(int timeoutMs) {
            synchronized (this) {
                while (!done) {
                    try {
                        if (timeoutMs < 1) {
                            this.wait();
                        } else {
                            this.wait(timeoutMs);
                            break;
                        }
                    } catch (InterruptedException e) {
                    }
                }
                return done;
            }
        }

        public void release() {
            synchronized (this) {
                done = true;
                this.notifyAll();
            }
        }

        public static final int CMD_LOAD = 0;
        public static final int CMD_INIT = 1;
        public static final int CMD_START = 2;
        public static final int CMD_STOP = 3;
        public static final int CMD_PAUSE = 4;
        public static final int CMD_RESUME = 5;
        public static final int CMD_NOTIFY_DESTROYED = 6;
        public static final int CMD_NOTIFY_PAUSED = 7;

        private int cmd;
        private Object arg;
        private boolean done = false;
    }
}
