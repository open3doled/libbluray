/*
 * This file is part of libbluray
 * States Test Xlet - tests interaction states (NORMAL, FOCUSED, ACTIONED, DISABLED)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 */

package org.videolan.test;

import java.awt.Color;
import java.awt.Font;
import java.awt.event.ActionEvent;

import javax.tv.service.SIManager;
import javax.tv.service.selection.ServiceContextFactory;
import javax.tv.xlet.Xlet;
import javax.tv.xlet.XletContext;
import javax.tv.xlet.XletStateChangeException;

import org.bluray.net.BDLocator;
import org.bluray.ti.Title;
import org.bluray.ti.selection.TitleContext;

import org.havi.ui.HScene;
import org.havi.ui.HSceneFactory;
import org.havi.ui.HState;
import org.havi.ui.HStaticText;
import org.havi.ui.HTextButton;
import org.havi.ui.HVisible;
import org.havi.ui.event.HActionListener;

/**
 * Tests interaction state rendering for buttons.
 * 
 * Demonstrates all interaction states with state-specific text content:
 * - NORMAL state (not focused)
 * - FOCUSED state (currently selected)
 * - ACTIONED state (toggled by Button 2)
 * - DISABLED state (toggled by Button 1)
 * 
 * Button 1: Press to toggle Button 3's enabled/disabled state
 * Button 2: Press to toggle Button 4's actioned state
 * Button 3: Demonstrates DISABLED state (starts disabled)
 * Button 4: Demonstrates ACTIONED state
 * 
 * Navigate between buttons to see state-specific text change.
 */
public class StatesTestXlet implements Xlet, HActionListener {

    private XletContext context;
    private HScene scene;
    private HStaticText titleLabel;
    private HStaticText descriptionLabel;
    private TestableButton[] testButtons;
    private HTextButton menuButton;
    
    /**
     * Subclass of HTextButton that exposes setInteractionState for testing.
     * setInteractionState is protected in HVisible, so we need this wrapper.
     */
    private static class TestableButton extends HTextButton {
        public TestableButton(String text, int x, int y, int w, int h) {
            super(text, x, y, w, h);
        }
        
        public void setStateForTesting(int state) {
            setInteractionState(state);
        }
    }
    
    // Layout constants
    private static final int BUTTON_WIDTH = 300;
    private static final int BUTTON_HEIGHT = 60;
    private static final int BUTTON_GAP = 20;

    public void initXlet(XletContext context) throws XletStateChangeException {
        System.err.println("StatesTestXlet: initXlet()");
        this.context = context;

        HSceneFactory factory = HSceneFactory.getInstance();
        scene = factory.getDefaultHScene();
        scene.setLayout(null);

        int sceneWidth = scene.getWidth();
        int sceneHeight = scene.getHeight();
        
        Font titleFont = new Font("SansSerif", Font.BOLD, 28);
        Font descFont = new Font("SansSerif", Font.PLAIN, 16);
        Font buttonFont = new Font("SansSerif", Font.BOLD, 20);
        Font menuFont = new Font("SansSerif", Font.PLAIN, 20);
        
        // Title
        titleLabel = new HStaticText("Interaction States Test", 0, 5, sceneWidth, 35);
        titleLabel.setFont(titleFont);
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        titleLabel.setVerticalAlignment(HVisible.VALIGN_CENTER);
        scene.add(titleLabel);
        
        // Description
        descriptionLabel = new HStaticText(
            "Btn1: toggle Btn3 disabled | Btn2: toggle Btn4 actioned | Text shows current state",
            20, 40, sceneWidth - 40, 35);
        descriptionLabel.setFont(descFont);
        descriptionLabel.setForeground(Color.LIGHT_GRAY);
        descriptionLabel.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        descriptionLabel.setVerticalAlignment(HVisible.VALIGN_CENTER);
        scene.add(descriptionLabel);
        
        // Create test buttons
        testButtons = new TestableButton[4];
        
        int startY = 85;
        int buttonX = (sceneWidth - BUTTON_WIDTH) / 2;
        
        for (int i = 0; i < 4; i++) {
            int y = startY + i * (BUTTON_HEIGHT + BUTTON_GAP);
            
            // Create button with initial text (will be overwritten by state-specific content)
            testButtons[i] = new TestableButton("Button " + (i + 1), buttonX, y, BUTTON_WIDTH, BUTTON_HEIGHT);
            testButtons[i].setFont(buttonFont);
            testButtons[i].setForeground(Color.WHITE);
            testButtons[i].setBackground(new Color(80, 80, 80));
            testButtons[i].setHorizontalAlignment(HVisible.HALIGN_CENTER);
            testButtons[i].setVerticalAlignment(HVisible.VALIGN_CENTER);
            testButtons[i].setActionCommand("button_" + (i + 1));
            testButtons[i].addHActionListener(this);
            
            scene.add(testButtons[i]);
        }
        
        // Set state-specific text content for each button
        setupButtonStateContent();
        
        // Button 3 starts disabled
        testButtons[2].setEnabled(false);
        
        // Set up navigation (circular through all 4 buttons)
        testButtons[0].setFocusTraversal(menuButton, testButtons[1], null, null);
        testButtons[1].setFocusTraversal(testButtons[0], testButtons[2], null, null);
        testButtons[2].setFocusTraversal(testButtons[1], testButtons[3], null, null);
        testButtons[3].setFocusTraversal(testButtons[2], menuButton, null, null);
        
        // Menu button at bottom
        int menuButtonWidth = 150;
        int menuButtonHeight = 40;
        menuButton = new HTextButton("Menu", 
            (sceneWidth - menuButtonWidth) / 2, 
            sceneHeight - menuButtonHeight - 20,
            menuButtonWidth, menuButtonHeight);
        menuButton.setFont(menuFont);
        menuButton.setForeground(Color.WHITE);
        menuButton.setBackground(new Color(80, 80, 80));
        menuButton.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        menuButton.setVerticalAlignment(HVisible.VALIGN_CENTER);
        menuButton.setActionCommand("menu");
        menuButton.addHActionListener(this);
        
        // Update navigation to include menu button
        testButtons[0].setFocusTraversal(menuButton, testButtons[1], null, null);
        testButtons[3].setFocusTraversal(testButtons[2], menuButton, null, null);
        menuButton.setFocusTraversal(testButtons[3], testButtons[0], null, null);
        
        scene.add(menuButton);

        System.err.println("StatesTestXlet: initXlet() complete");
    }
    
    /**
     * Sets up state-specific text content for all buttons.
     * Each button shows its current state in its text.
     */
    private void setupButtonStateContent() {
        // Button 1: Toggle Btn3 disabled
        testButtons[0].setTextContent("Btn1: NORMAL (toggles Btn3)", HState.NORMAL_STATE);
        testButtons[0].setTextContent("Btn1: FOCUSED (toggles Btn3)", HState.FOCUSED_STATE);
        testButtons[0].setTextContent("Btn1: ACTIONED", HState.ACTIONED_STATE);
        testButtons[0].setTextContent("Btn1: ACT+FOCUS", HState.ACTIONED_FOCUSED_STATE);
        testButtons[0].setTextContent("Btn1: DISABLED", HState.DISABLED_STATE);
        testButtons[0].setTextContent("Btn1: DIS+FOCUS", HState.DISABLED_FOCUSED_STATE);
        testButtons[0].setTextContent("Btn1: DIS+ACT", HState.DISABLED_ACTIONED_STATE);
        testButtons[0].setTextContent("Btn1: DIS+ACT+FOCUS", HState.DISABLED_ACTIONED_FOCUSED_STATE);
        
        // Button 2: Toggle Btn4 actioned
        testButtons[1].setTextContent("Btn2: NORMAL (toggles Btn4)", HState.NORMAL_STATE);
        testButtons[1].setTextContent("Btn2: FOCUSED (toggles Btn4)", HState.FOCUSED_STATE);
        testButtons[1].setTextContent("Btn2: ACTIONED", HState.ACTIONED_STATE);
        testButtons[1].setTextContent("Btn2: ACT+FOCUS", HState.ACTIONED_FOCUSED_STATE);
        testButtons[1].setTextContent("Btn2: DISABLED", HState.DISABLED_STATE);
        testButtons[1].setTextContent("Btn2: DIS+FOCUS", HState.DISABLED_FOCUSED_STATE);
        testButtons[1].setTextContent("Btn2: DIS+ACT", HState.DISABLED_ACTIONED_STATE);
        testButtons[1].setTextContent("Btn2: DIS+ACT+FOCUS", HState.DISABLED_ACTIONED_FOCUSED_STATE);
        
        // Button 3: Shows disabled state (toggled by Btn1)
        testButtons[2].setTextContent("Btn3: NORMAL", HState.NORMAL_STATE);
        testButtons[2].setTextContent("Btn3: FOCUSED", HState.FOCUSED_STATE);
        testButtons[2].setTextContent("Btn3: ACTIONED", HState.ACTIONED_STATE);
        testButtons[2].setTextContent("Btn3: ACT+FOCUS", HState.ACTIONED_FOCUSED_STATE);
        testButtons[2].setTextContent("Btn3: DISABLED", HState.DISABLED_STATE);
        testButtons[2].setTextContent("Btn3: DIS+FOCUS", HState.DISABLED_FOCUSED_STATE);
        testButtons[2].setTextContent("Btn3: DIS+ACT", HState.DISABLED_ACTIONED_STATE);
        testButtons[2].setTextContent("Btn3: DIS+ACT+FOCUS", HState.DISABLED_ACTIONED_FOCUSED_STATE);
        
        // Button 4: Shows actioned state (toggled by Btn2)
        testButtons[3].setTextContent("Btn4: NORMAL", HState.NORMAL_STATE);
        testButtons[3].setTextContent("Btn4: FOCUSED", HState.FOCUSED_STATE);
        testButtons[3].setTextContent("Btn4: ACTIONED", HState.ACTIONED_STATE);
        testButtons[3].setTextContent("Btn4: ACT+FOCUS", HState.ACTIONED_FOCUSED_STATE);
        testButtons[3].setTextContent("Btn4: DISABLED", HState.DISABLED_STATE);
        testButtons[3].setTextContent("Btn4: DIS+FOCUS", HState.DISABLED_FOCUSED_STATE);
        testButtons[3].setTextContent("Btn4: DIS+ACT", HState.DISABLED_ACTIONED_STATE);
        testButtons[3].setTextContent("Btn4: DIS+ACT+FOCUS", HState.DISABLED_ACTIONED_FOCUSED_STATE);
    }

    public void startXlet() throws XletStateChangeException {
        System.err.println("StatesTestXlet: startXlet()");
        scene.setVisible(true);
        testButtons[0].requestFocus();
        scene.repaint();
    }

    public void pauseXlet() {
        System.err.println("StatesTestXlet: pauseXlet()");
    }

    public void destroyXlet(boolean force) throws XletStateChangeException {
        System.err.println("StatesTestXlet: destroyXlet()");
        if (scene != null) {
            scene.setVisible(false);
            scene.removeAll();
            scene.dispose();
            scene = null;
        }
    }

    public void actionPerformed(ActionEvent e) {
        String command = e.getActionCommand();
        System.err.println("StatesTestXlet: actionPerformed(" + command + ")");
        
        if ("menu".equals(command)) {
            returnToMenu();
        } else if ("button_1".equals(command)) {
            // Toggle Button 3's enabled/disabled state
            boolean newEnabled = !testButtons[2].isEnabled();
            testButtons[2].setEnabled(newEnabled);
            testButtons[2].repaint();
            System.err.println("StatesTestXlet: Button 3 enabled=" + newEnabled);
        } else if ("button_2".equals(command)) {
            // Toggle Button 4's actioned state bit
            int state = testButtons[3].getInteractionState();
            if ((state & HState.ACTIONED_STATE_BIT) != 0) {
                // Clear actioned bit
                testButtons[3].setStateForTesting(state & ~HState.ACTIONED_STATE_BIT);
                System.err.println("StatesTestXlet: Button 4 actioned=false");
            } else {
                // Set actioned bit
                testButtons[3].setStateForTesting(state | HState.ACTIONED_STATE_BIT);
                System.err.println("StatesTestXlet: Button 4 actioned=true");
            }
            testButtons[3].repaint();
        }
    }
    
    private void returnToMenu() {
        try {
            BDLocator locator = new BDLocator(null, 0, -1);
            Title title = (Title) SIManager.createInstance().getService(locator);
            if (title != null) {
                TitleContext titleContext = (TitleContext) ServiceContextFactory.getInstance().getServiceContext(null);
                if (titleContext != null) {
                    titleContext.start(title, true);
                }
            }
        } catch (Exception ex) {
            System.err.println("StatesTestXlet: Error returning to menu: " + ex.getMessage());
        }
    }
}
