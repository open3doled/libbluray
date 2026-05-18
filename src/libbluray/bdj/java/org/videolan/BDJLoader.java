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

package org.videolan;

import java.awt.BDJHelper;
import java.io.File;
import java.io.InputStream;
import java.io.InvalidObjectException;
import java.util.Enumeration;
import org.videolan.Logger;

import org.bluray.net.BDLocator;
import org.bluray.system.RegisterAccess;
import org.bluray.ti.TitleImpl;
import org.davic.media.MediaLocator;
import org.dvb.application.AppID;
import org.dvb.application.AppsDatabase;
import org.dvb.application.CurrentServiceFilter;

import javax.media.Manager;
import javax.tv.locator.Locator;

import org.videolan.bdjo.AppEntry;
import org.videolan.bdjo.Bdjo;
import org.videolan.bdjo.GraphicsResolution;
import org.videolan.bdjo.PlayListTable;
import org.videolan.bdjo.TerminalInfo;
import org.videolan.media.content.PlayerManager;

public class BDJLoader {

    private static class FontCacheAction extends BDJAction {
        public FontCacheAction(InputStream is) {
            this.fontPath = null;
            this.is = is;
        }

        public FontCacheAction(String fontPath) {
            this.fontPath = fontPath;
            this.is = null;
        }

        protected void doAction() {
            try {
                if (this.is != null) {
                    this.cacheFile = addFontImpl(is);
                } else {
                    this.cacheFile = addFontImpl(fontPath);
                }
            } catch (RuntimeException e) {
                this.exception = e;
            }
        }

        public File execute() {
            BDJActionManager.getInstance().putCommand(this);
            waitEnd();
            if (exception != null) {
                throw exception;
            }
            return cacheFile;
        }

        private final String fontPath;
        private final InputStream is;
        private File cacheFile = null;
        private RuntimeException exception = null;
    }

    /* called by org.dvb.ui.FontFactory */
    public static File addFont(InputStream is) {
        if (BDJXletContext.getCurrentContext() == null)
            return addFontImpl(is);
        /* dispatch cache request to privileged thread */
        return new FontCacheAction(is).execute();
    }

    /* called by org.dvb.ui.FontFactory */
    public static File addFont(String fontFile) {
        if (BDJXletContext.getCurrentContext() == null)
            return addFontImpl(fontFile);
        /* dispatch cache request to privileged thread */
        return new FontCacheAction(fontFile).execute();
    }

    private static File addFontImpl(InputStream is) {
        VFSCache localCache = vfsCache;
        if (localCache != null) {
            return localCache.addFont(is);
        }
        return null;
    }

    private static File addFontImpl(String fontFile) {
        VFSCache localCache = vfsCache;
        if (localCache != null) {
            return localCache.addFont(fontFile);
        }
        return null;
    }

    /* called by BDJSecurityManager */
    protected static void accessFile(String file) {
        VFSCache localCache = vfsCache;
        if (localCache != null) {
            localCache.accessFile(file);
        }
    }

    public static String getCachedFile(String path) {
        VFSCache localCache = vfsCache;
        if (localCache != null) {
            return localCache.map(path);
        }
        return path;
    }

    public static String[] getCachedFiles(String path) {
        VFSCache localCache = vfsCache;
        if (localCache != null) {
            return localCache.list(path);
        }
        return null;
    }

    public static boolean cacheVFSAssets(BUMFAsset[] assets) {
        if (assets == null) {
            return false;
        }

        VFSCache localCache = getVFSCache();
        if (localCache == null) {
            logger.error("cacheVFSAssets(): VFS cache is not available");
            return false;
        }

        for (int i = 0; i < assets.length; i++) {
            BUMFAsset asset = assets[i];
            if (asset == null) {
                logger.error("cacheVFSAssets(): null asset at index " + i);
                return false;
            }
            if (!localCache.add(asset.getVpFile(), asset.getBudaFile())) {
                logger.error("cacheVFSAssets(): failed to cache " +
                             asset.getVpFile() + " <- " + asset.getBudaFile());
                return false;
            }
        }

        return true;
    }

    public static boolean load(TitleImpl title, boolean restart, BDJLoaderCallback callback) {
        // This method should be called only from ServiceContextFactory

        if (title == null)
            return false;
        synchronized (BDJLoader.class) {
            if (queue == null)
                queue = BDJActionQueue.create("BDJLoader");
        }
        queue.put(new BDJLoaderAction(title, restart, callback));
        return true;
    }

    public static boolean unload(BDJLoaderCallback callback) {
        // This method should be called only from ServiceContextFactory

        synchronized (BDJLoader.class) {
            if (queue == null)
                queue = BDJActionQueue.create("BDJLoader");
        }
        queue.put(new BDJLoaderAction(null, false, callback));
        return true;
    }

    protected static void shutdown() {
        try {
            if (queue != null) {
                queue.shutdown();
            }
        } catch (Throwable e) {
            logger.error("shutdown() failed: " + e + "\n" + Logger.dumpStack(e));
        }
        queue = null;
        vfsCache = null;
    }

    private static VFSCache getVFSCache() {
        VFSCache localCache = vfsCache;
        if (localCache != null) {
            return localCache;
        }

        synchronized (BDJLoader.class) {
            if (vfsCache == null) {
                vfsCache = VFSCache.createInstance();
            }
            return vfsCache;
        }
    }

    private static boolean loadN(TitleImpl title, boolean restart) {
        BDJDebug.traceLifecycle(logger, "loadN title=" + title.getTitleNum() +
                                " restart=" + restart +
                                BDJHelper.describeDiscState());

        if (getVFSCache() == null) {
            logger.error("loadN(): VFS cache is not available");
            return false;
        }

        TitleInfo ti = title.getTitleInfo();
        if (!ti.isBdj()) {
            logger.info("Not BD-J title - requesting HDMV title start");
            BDJDebug.traceLifecycle(logger, "loadN hdmvRedirect title=" +
                                    title.getTitleNum() +
                                    BDJHelper.describeDiscState());
            unloadN();
            boolean selected = Libbluray.selectHdmvTitle(title.getTitleNum());
            BDJDebug.traceLifecycle(logger, "loadN hdmvRedirectResult title=" +
                                    title.getTitleNum() +
                                    " selected=" + selected +
                                    BDJHelper.describeDiscState());
            return selected;
        }

        try {
            // load bdjo
            Bdjo bdjo = Libbluray.getBdjo(ti.getBdjoName());
            if (bdjo == null)
                throw new InvalidObjectException("bdjo not loaded");
            AppEntry[] appTable = bdjo.getAppTable();
            BDJDebug.traceLifecycle(logger, "bdjo loaded title=" + title.getTitleNum() + " bdjo=" + ti.getBdjoName() + " appCount=" + appTable.length);

            // reuse appProxys
            BDJAppProxy[] proxys = new BDJAppProxy[appTable.length];
            AppsDatabase db = AppsDatabase.getAppsDatabase();
            Enumeration ids = db.getAppIDs(new CurrentServiceFilter());
            while (ids.hasMoreElements()) {
                AppID id = (AppID)ids.nextElement();
                BDJAppProxy proxy = (BDJAppProxy)db.getAppProxy(id);
                AppEntry entry = (AppEntry)db.getAppAttributes(id);
                if (proxy == null) {
                    logger.error("AppsDatabase corrupted!");
                    continue;
                }
                if (entry == null) {
                    logger.error("AppsDatabase corrupted!");
                    proxy.release();
                    continue;
                }
                for (int i = 0; i < appTable.length; i++) {
                    if (id.equals(appTable[i].getIdentifier()) &&
                        entry.getInitialClass().equals(appTable[i].getInitialClass())) {
                        if (restart && appTable[i].getIsServiceBound()) {
                            logger.info("Stopping xlet " + appTable[i].getInitialClass() + " (for restart)");
                            proxy.stop(true);
                        } else {
                            logger.info("Keeping xlet " + appTable[i].getInitialClass());
                            proxys[i] = proxy;
                            proxy = null;
                        }
                        break;
                    }
                }
                if (proxy != null) {
                    logger.info("Terminating xlet " + entry.getInitialClass());
                    proxy.release();
                }
            }

            // start bdj window
            GUIManager gui = GUIManager.createInstance();
            TerminalInfo terminfo = bdjo.getTerminalInfo();
            GraphicsResolution res = terminfo.getResolution();
            gui.setDefaultFont(terminfo.getDefaultFont());
            gui.setResizable(true);
            gui.setSize(res.getWidth(), res.getHeight());
            gui.setVisible(true);

            Libbluray.setUOMask(terminfo.getMenuCallMask(), terminfo.getTitleSearchMask());
            Libbluray.setKeyInterest(bdjo.getKeyInterestTable());

            // initialize AppCaches
            if (vfsCache != null) {
                vfsCache.add(bdjo.getAppCaches());
            }

            try {
                BDJLoaderAdapter a = Libbluray.getLoaderAdapter();
                if (a != null)
                    appTable = a.patchAppTable(appTable, title.getTitleNum());
            } catch (Throwable t) {
                logger.error("" + t);
            }

            // initialize appProxys
            boolean[] reusedProxy = new boolean[appTable.length];
            for (int i = 0; i < appTable.length; i++) {
                if (proxys[i] == null) {
                    proxys[i] = BDJAppProxy.newInstance(new BDJXletContext(appTable[i], bdjo.getAppCaches(), gui));

                    /* log startup class, startup parameters and jar file */
                    String[] params = appTable[i].getParams();
                    String p = "";
                    if (params != null && params.length > 0) {
                        p = "(" + StrUtil.Join(params, ",") + ")";
                    }
                    logger.info("Loaded class: " + appTable[i].getInitialClass() + p + " from " + appTable[i].getBasePath() + ".jar");
                } else {
                    reusedProxy[i] = true;
                    proxys[i].getXletContext().update(appTable[i], bdjo.getAppCaches());
                    logger.info("Reused class: " + appTable[i].getInitialClass() +     " from " + appTable[i].getBasePath() + ".jar");
                }
            }

            // change psr
            Libbluray.writePSR(RegisterAccess.PSR_TITLE_NR, title.getTitleNum());

            // notify AppsDatabase
            ((BDJAppsDatabase)BDJAppsDatabase.getAppsDatabase()).newDatabase(bdjo, proxys);

            // auto start playlist
            try {
                PlayListTable plt = bdjo.getAccessiblePlaylists();
                if ((plt != null) && (plt.isAutostartFirst())) {
                    logger.info("Auto-starting playlist");
                    String[] pl = plt.getPlayLists();
                    if (pl.length > 0) {
                        BDJDebug.traceLifecycle(logger, "autoplay playlist title=" + title.getTitleNum() + " playlist=" + pl[0]);
                        Manager.createPlayer(new MediaLocator(new BDLocator("bd://PLAYLIST:" + pl[0]))).start();
                    } else {
                        BDJDebug.traceLifecycle(logger, "autoplay requested but no playlists were listed");
                    }
                }
            } catch (Exception e) {
                logger.error("loadN(): autoplaylist failed: " + e + "\n" + Logger.dumpStack(e));
            }

            // now run all the xlets
            for (int i = 0; i < appTable.length; i++) {
                int code = appTable[i].getControlCode();
                int proxyState = proxys[i].getState();
                if (code == AppEntry.AUTOSTART) {
                    if (reusedProxy[i] && proxyState == BDJAppProxy.STARTED) {
                        logger.info("Keeping running AUTOSTART xlet " + i + ": " + appTable[i].getInitialClass());
                        BDJDebug.traceLifecycle(logger, "keeping running AUTOSTART xlet index=" + i +
                                                " class=" + appTable[i].getInitialClass() +
                                                " state=" + proxyState);
                        continue;
                    }
                    logger.info("Autostart xlet " + i + ": " + appTable[i].getInitialClass());
                    BDJDebug.traceLifecycle(logger, "starting AUTOSTART xlet index=" + i +
                                            " class=" + appTable[i].getInitialClass() +
                                            " state=" + proxyState +
                                            " reused=" + reusedProxy[i]);
                    proxys[i].start();
                } else if (code == AppEntry.PRESENT) {
                    if (reusedProxy[i] &&
                        (proxyState == BDJAppProxy.STARTED || proxyState == BDJAppProxy.PAUSED)) {
                        logger.info("Keeping initialized PRESENT xlet " + i + ": " + appTable[i].getInitialClass());
                        BDJDebug.traceLifecycle(logger, "keeping initialized PRESENT xlet index=" + i +
                                                " class=" + appTable[i].getInitialClass() +
                                                " state=" + proxyState);
                        continue;
                    }
                    logger.info("Init xlet " + i + ": " + appTable[i].getInitialClass());
                    BDJDebug.traceLifecycle(logger, "initializing PRESENT xlet index=" + i +
                                            " class=" + appTable[i].getInitialClass() +
                                            " state=" + proxyState +
                                            " reused=" + reusedProxy[i]);
                    proxys[i].init();
                } else {
                    logger.info("Unsupported xlet code (" +code+") xlet " + i + ": " + appTable[i].getInitialClass());
                    BDJDebug.traceLifecycle(logger, "unsupported xlet code=" + code + " class=" + appTable[i].getInitialClass());
                }
            }

            logger.info("Finished initializing and starting xlets.");
            BDJDebug.traceLifecycle(logger, "loadN complete title=" + title.getTitleNum());

            return true;

        } catch (Throwable e) {
            logger.error("loadN() failed: " + e + "\n" + Logger.dumpStack(e));
            unloadN();
            return false;
        }
    }

    private static boolean unloadN() {
        try {
            BDJDebug.traceLifecycle(logger, "unloadN begin");
            try {
                GUIManager.getInstance().setVisible(false);
            } catch (Error e) {
            }

            AppsDatabase db = AppsDatabase.getAppsDatabase();

            /* stop xlets first */
            Enumeration ids = db.getAppIDs(new CurrentServiceFilter());
            while (ids.hasMoreElements()) {
                AppID id = (AppID)ids.nextElement();
                BDJAppProxy proxy = (BDJAppProxy)db.getAppProxy(id);
                if (proxy != null) {
                    BDJDebug.traceLifecycle(logger, "unloadN stop proxy=" + proxy.getXletContext());
                    proxy.stop(true);
                }
            }

            ids = db.getAppIDs(new CurrentServiceFilter());
            while (ids.hasMoreElements()) {
                AppID id = (AppID)ids.nextElement();
                BDJAppProxy proxy = (BDJAppProxy)db.getAppProxy(id);
                if (proxy != null) {
                    BDJDebug.traceLifecycle(logger, "unloadN release proxy=" + proxy.getXletContext());
                    proxy.release();
                }
            }

            ((BDJAppsDatabase)db).newDatabase(null, null);

            PlayerManager.getInstance().releaseAllPlayers(true);
            BDJDebug.traceLifecycle(logger, "unloadN complete");

            return true;
        } catch (Throwable e) {
            logger.error("unloadN() failed: " + e + "\n" + Logger.dumpStack(e));
            return false;
        }
    }

    private static class BDJLoaderAction extends BDJAction {
        public BDJLoaderAction(TitleImpl title, boolean restart, BDJLoaderCallback callback) {
            this.title = title;
            this.restart = restart;
            this.callback = callback;
        }

        protected void doAction() {
            boolean succeed;
            if (title != null)
                succeed = loadN(title, restart);
            else
                succeed = unloadN();
            if (callback != null)
                callback.loaderDone(succeed);
        }

        private TitleImpl title;
        private boolean restart;
        private BDJLoaderCallback callback;
    }

    private static final Logger logger = Logger.getLogger(BDJLoader.class.getName());

    private static BDJActionQueue queue = null;
    private static VFSCache       vfsCache = null;
}
