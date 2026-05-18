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

package org.videolan.media.content.playlist;

import java.awt.Component;

import javax.media.ClockStartedError;
import javax.tv.locator.InvalidLocatorException;
import javax.tv.locator.Locator;

import org.bluray.system.RegisterAccess;
import org.bluray.media.InvalidPlayListException;
import org.bluray.media.PlayListChangeControl;
import org.bluray.net.BDLocator;
import org.bluray.ti.PlayList;
import org.videolan.BDJXletContext;

public class PlayListChangeControlImpl implements PlayListChangeControl {
    protected PlayListChangeControlImpl(Handler player) {
        this.player = player;
    }

    public Component getControlComponent() {
        return null;
    }

    public void selectPlayList(PlayList pl) throws InvalidPlayListException, ClockStartedError {
        try {
            System.err.println("TRACE playlistChangeControl path=playList caller=" + _callerString() +
                               " ctx=" + _contextString() +
                               " state=" + _menuBranchState() +
                               " locator=" + ((BDLocator)pl.getLocator()).toExternalForm());
            player.selectPlayList((BDLocator)pl.getLocator());
        } catch (InvalidLocatorException e) {
            throw new InvalidPlayListException();
        }
    }

    public void selectPlayList(BDLocator locator)
            throws InvalidPlayListException, InvalidLocatorException, ClockStartedError {
        System.err.println("TRACE playlistChangeControl path=bdLocator caller=" + _callerString() +
                           " ctx=" + _contextString() +
                           " state=" + _menuBranchState() +
                           " locator=" + (locator != null ? locator.toExternalForm() : "<null>"));
        player.selectPlayList(locator);
    }

    public BDLocator getCurrentPlayList() {
        Locator[] locators = player.getServiceContentLocators();
        if ((locators == null) || (locators.length <= 0)) {
            System.err.println("TRACE playlistChangeControl path=getCurrent caller=" + _callerString() +
                               " ctx=" + _contextString() +
                               " locator=<null>");
            return null;
        }
        BDLocator locator = (BDLocator)locators[0];
        System.err.println("TRACE playlistChangeControl path=getCurrent caller=" + _callerString() +
                           " ctx=" + _contextString() +
                           " locator=" + locator.toExternalForm());
        return locator;
    }

    private Handler player;

    private static String _callerString() {
        StackTraceElement[] stack = new Exception().getStackTrace();
        for (int i = 1; i < stack.length; i++) {
            String cls = stack[i].getClassName();
            if (!cls.equals(PlayListChangeControlImpl.class.getName()) &&
                !cls.startsWith("java.lang.")) {
                return cls + "." + stack[i].getMethodName() + ":" + stack[i].getLineNumber();
            }
        }
        return "<unknown>";
    }

    private static String _contextString() {
        BDJXletContext ctx = BDJXletContext.getCurrentContext();
        return ctx != null ? ctx.toString() : "<null>";
    }

    private static String _menuBranchState() {
        RegisterAccess regs = RegisterAccess.getInstance();
        return "gpr100=" + regs.getGPR(100) +
               " gpr104=" + regs.getGPR(104) +
               " gpr105=" + regs.getGPR(105) +
               " gpr110=" + regs.getGPR(110) +
               " psr_title=" + regs.getPSR(RegisterAccess.PSR_TITLE_NR) +
               " psr_playlist=" + regs.getPSR(RegisterAccess.PSR_PLAYLIST_ID);
    }
}
