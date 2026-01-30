/*
 * This file is part of libbluray
 * Copyright (C) 2024 libbluray project
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
 *
 * Based on XletView implementation by Martin Sveden.
 */

package org.havi.ui;

import java.awt.Component;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.util.Hashtable;

import org.havi.ui.event.HFocusEvent;
import org.havi.ui.event.HFocusListener;

/**
 * Helper class that implements HNavigable functionality.
 * This encapsulates the navigation logic for use by HIcon, HGraphicButton, etc.
 */
public final class HNavigableHelper {

    private Hashtable navTargets;
    private HSound gainFocusSound;
    private HSound loseFocusSound;
    transient HFocusListener hFocusListener;

    private HVisible hVisible;

    /**
     * Singleton FocusListener to ensure AWT focus events are delivered.
     * AWT requires at least one FocusListener to be registered for focus events
     * to be delivered to a component.
     */
    private static final FocusListener FOCUS_LISTENER_DUMMY = new FocusListener() {
        public void focusGained(FocusEvent e) {
            // Triggers AWT focus mechanism - actual handling done in processHFocusEvent
        }
        public void focusLost(FocusEvent e) {
            // Triggers AWT focus mechanism - actual handling done in processHFocusEvent
        }
    };

    public HNavigableHelper(HVisible hVisible) {
        this.hVisible = hVisible;

        /*
         * Since the Component will not get focus unless there is
         * a FocusListener registered we "secretly" add one.
         * This ensures the AWT focus mechanism triggers focus events.
         */
        hVisible.addFocusListener(FOCUS_LISTENER_DUMMY);
    }

    /**
     * Sets a navigation target for the specified key code.
     * @param keyCode The key code (e.g., KeyEvent.VK_UP)
     * @param target The HNavigable to transfer focus to, or null to remove
     */
    public void setMove(int keyCode, HNavigable target) {
        if (navTargets == null) {
            navTargets = new Hashtable();
        }
        Integer code = Integer.valueOf(keyCode);
        // Remove existing target if present
        if (navTargets.containsKey(code)) {
            navTargets.remove(code);
        }
        // Add new target (only if non-null)
        if (target != null) {
            navTargets.put(code, target);
        }
    }

    /**
     * Gets the navigation target for the specified key code.
     * @param keyCode The key code
     * @return The HNavigable target, or null if not set
     */
    public HNavigable getMove(int keyCode) {
        if (navTargets == null) {
            return null;
        }
        return (HNavigable) navTargets.get(Integer.valueOf(keyCode));
    }

    /**
     * Sets navigation targets for all four arrow keys at once.
     */
    public void setFocusTraversal(HNavigable up, HNavigable down, HNavigable left, HNavigable right) {
        setMove(KeyEvent.VK_UP, up);
        setMove(KeyEvent.VK_DOWN, down);
        setMove(KeyEvent.VK_LEFT, left);
        setMove(KeyEvent.VK_RIGHT, right);
    }

    /**
     * Returns true if this component currently has focus.
     */
    public boolean isSelected() {
        return hVisible.hasFocus();
    }

    public void setGainFocusSound(HSound sound) {
        gainFocusSound = sound;
    }

    public void setLoseFocusSound(HSound sound) {
        loseFocusSound = sound;
    }

    public HSound getGainFocusSound() {
        return gainFocusSound;
    }

    public HSound getLoseFocusSound() {
        return loseFocusSound;
    }

    /**
     * Adds an HFocusListener to receive focus events.
     */
    public synchronized void addHFocusListener(HFocusListener listener) {
        if (listener == null) {
            return;
        }
        hFocusListener = HEventMulticaster.add(hFocusListener, listener);
    }

    /**
     * Removes an HFocusListener.
     */
    public synchronized void removeHFocusListener(HFocusListener listener) {
        if (listener == null) {
            return;
        }
        hFocusListener = HEventMulticaster.remove(hFocusListener, listener);
    }

    /**
     * Returns an array of key codes for which navigation targets are set.
     */
    public int[] getNavigationKeys() {
        if (navTargets == null || navTargets.size() == 0) {
            return null;
        }
        int[] keyCodes = new int[navTargets.size()];
        java.util.Enumeration keys = navTargets.keys();
        int i = 0;
        while (keys.hasMoreElements()) {
            Integer key = (Integer) keys.nextElement();
            keyCodes[i++] = key.intValue();
        }
        return keyCodes;
    }

    /**
     * Processes an HFocusEvent and returns the new interaction state.
     * This is the core method that handles focus gain/loss and focus transfers.
     *
     * @param evt The HFocusEvent to process
     * @return The new interaction state for the HVisible
     */
    public int processHFocusEvent(HFocusEvent evt) {
        int state = hVisible.getInteractionState();

        if (evt.getID() == FocusEvent.FOCUS_GAINED) {
            // Set focused bit
            state = state | HState.FOCUSED_STATE_BIT;

            // Play gain focus sound
            if (gainFocusSound != null) {
                gainFocusSound.play();
            }

            // Notify HFocusListeners
            if (hFocusListener != null) {
                hFocusListener.focusGained(evt);
            }
        }
        else if (evt.getID() == FocusEvent.FOCUS_LOST) {
            // Clear focused bit (XOR to toggle off)
            state = state & (~HState.FOCUSED_STATE_BIT);

            // Play lose focus sound
            if (loseFocusSound != null) {
                loseFocusSound.play();
            }

            // Notify HFocusListeners
            if (hFocusListener != null) {
                hFocusListener.focusLost(evt);
            }
        }
        else if (evt.getID() == HFocusEvent.FOCUS_TRANSFER &&
                 evt.getTransferId() != HFocusEvent.NO_TRANSFER_ID) {
            // Handle focus transfer to navigation target
            HNavigable newNav = getMove(evt.getTransferId());

            if (newNav instanceof Component) {
                ((Component) newNav).requestFocus();
            }
        }

        return state;
    }

    /**
     * Returns FocusListeners excluding the internal dummy listener.
     * This allows the component to report only user-registered listeners.
     */
    public synchronized FocusListener[] getFocusListeners() {
        // Temporarily remove our dummy, get the list, then re-add
        hVisible.removeFocusListener(FOCUS_LISTENER_DUMMY);
        FocusListener[] listeners = (FocusListener[]) hVisible.getListeners(FocusListener.class);
        hVisible.addFocusListener(FOCUS_LISTENER_DUMMY);
        return listeners;
    }

    /**
     * Processes a KeyEvent for navigation.
     * Handles arrow keys by looking up navigation targets and transferring focus.
     *
     * @param e The KeyEvent to process
     * @return true if the event was handled, false otherwise
     */
    public boolean processKeyEvent(KeyEvent e) {
        // Only handle KEY_PRESSED
        if (e.getID() != KeyEvent.KEY_PRESSED) {
            return false;
        }

        int keyCode = e.getKeyCode();

        // Only handle arrow keys
        if (keyCode != KeyEvent.VK_UP && keyCode != KeyEvent.VK_DOWN &&
            keyCode != KeyEvent.VK_LEFT && keyCode != KeyEvent.VK_RIGHT) {
            return false;
        }

        // Look up navigation target
        HNavigable target = getMove(keyCode);

        if (target != null && target instanceof Component) {
            Component targetComponent = (Component) target;
            targetComponent.requestFocus();
            return true;
        }

        return false;
    }
}
