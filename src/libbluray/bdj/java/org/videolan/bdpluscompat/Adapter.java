/*
 * This file is part of libbluray
 * Copyright (C) 2026  Open3DOLED
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

package org.videolan.bdpluscompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.Map;

import javax.media.ClockStartedError;
import javax.media.Manager;
import javax.media.NoPlayerException;
import javax.media.Player;
import javax.tv.xlet.XletContext;
import javax.tv.xlet.XletStateChangeException;

import org.bluray.bdplus.StatusListener;
import org.videolan.BDJClassFileTransformer;
import org.videolan.BDJClassLoaderAdapter;
import org.videolan.BDJXletContext;
import org.videolan.Logger;

public class Adapter implements BDJClassLoaderAdapter {

    public Map getHideClasses() {
        return null;
    }

    public Map getBootClasses() {
        return bootClasses;
    }

    public Map getXletClasses() {
        return null;
    }

    public Adapter() throws ClassNotFoundException {

        if (BDJXletContext.getCurrentContext() != null)
            throw new ClassNotFoundException();

        final String source = "org/videolan/bdpluscompat/Adapter$MVSupport";
        final String target = "com/macrovision/bdplus/MVSupport";

        Map renameMap = new HashMap();
        renameMap.put(source, target);

        BDJClassFileTransformer t = new BDJClassFileTransformer();
        byte[] code = t.rename(loadBootClassCode(source), renameMap);
        if (code != null) {
            bootClasses = new HashMap();
            bootClasses.put(target.replace('/', '.'), code);
        }
    }

    public static class MVSupport implements javax.tv.xlet.Xlet, StatusListener {
        private static MVSupport instance = new MVSupport();
        private javax.tv.xlet.Xlet xlet = null;
        private XletContext ctx = null;

        static private void error(String s) {
            System.err.println(s);
        }

        public static MVSupport initXlet(javax.tv.xlet.Xlet xlet, XletContext ctx) throws XletStateChangeException {
            if ((xlet == null) || (ctx == null)) {
                error("initXlet: null argument");
                throw new XletStateChangeException();
            }
            if ((instance.xlet == xlet) && (instance.ctx == ctx)) {
                error("initXlet: invalid arguments");
                throw new XletStateChangeException();
            }
            instance.xlet = xlet;
            instance.ctx = ctx;
            return instance;
        }

        public static MVSupport lookup(XletContext ctx) {
            return instance;
        }

        public void initXlet(XletContext ctx) throws XletStateChangeException {
            if (ctx == null) {
                error("initXlet: null context");
                throw new XletStateChangeException();
            }
            this.xlet = this;
            this.ctx = ctx;
        }

        public void startXlet() throws XletStateChangeException {
        }

        public void pauseXlet() {
        }

        public void destroyXlet(boolean a) {
            this.instance.xlet = null;
            this.instance.ctx = null;
        }

        public boolean isAuthRequired() {
            return false;
        }

        public void doAuth() throws InterruptedException {
            doAuth(null);
        }

        public synchronized void doAuth(Object a) throws InterruptedException, RuntimeException {
            error("doAuth");
        }

        public void destroy() {
        }

        public void receive(int i) {
        }

        public byte[] getMMV() {
            byte[] b = new byte[8];
            new java.util.Random().nextBytes(b);
            return b;
        }

        public Player createPlayer(javax.media.protocol.DataSource s) throws IOException, NoPlayerException {
            return Manager.createPlayer(s);
        }

        public Player createPlayer(javax.media.MediaLocator l) throws IOException, NoPlayerException {
            return Manager.createPlayer(l);
        }

        public Player createPlayer(java.net.URL u) throws IOException, NoPlayerException {
            return Manager.createPlayer(u);
        }

        public void destroyPlayer(Player p) {
            if (p == null)
                return;
            p.stop();
            try {
                p.deallocate();
            } catch (ClockStartedError localClockStartedError) {
            }
            p.close();
        }
    }

    private byte[] loadBootClassCode(String name) throws ClassNotFoundException {
        final String path = name.replace('.', '/').concat(".class");
        InputStream is = null;
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        byte[] buffer = new byte[0xffff];
        try {
            is = (InputStream)
            AccessController.doPrivileged(
                new PrivilegedAction() {
                    public Object run() {
                        return ClassLoader.getSystemResourceAsStream(path);
                    }
                });

            if (is == null) {
                logger.error("loadBootClassCode(" + name + "): not found");
                throw new ClassNotFoundException(name);
            }

            while (true) {
                int r = is.read(buffer);
                if (r == -1) {
                    break;
                }
                os.write(buffer, 0, r);
            }

            return os.toByteArray();

        } catch (Exception e) {
            logger.error("loadBootClassCode(" + name + ") failed: " + e);
            throw new ClassNotFoundException(name);

        } finally {
            try {
                if (is != null)
                    is.close();
            } catch (IOException ioe) {
            }
            try {
                if (os != null)
                    os.close();
            } catch (IOException ioe) {
            }
        }
    }

    private Map bootClasses = new HashMap();

    private static final Logger logger = Logger.getLogger(Adapter.class.getName());
}
