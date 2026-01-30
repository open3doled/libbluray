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
import org.havi.ui.HStaticText;
import org.havi.ui.HTextButton;
import org.havi.ui.HVisible;
import org.havi.ui.event.HActionListener;

/**
 * Tests interaction state rendering for buttons.
 * 
 * Shows multiple buttons demonstrating:
 * - NORMAL state (not focused)
 * - FOCUSED state (currently selected)
 * - ACTIONED state (being pressed)
 * - DISABLED state (not interactive)
 * 
 * Navigate between buttons to see state changes.
 */
public class StatesTestXlet implements Xlet, HActionListener {

    private XletContext context;
    private HScene scene;
    private HStaticText titleLabel;
    private HStaticText descriptionLabel;
    private HTextButton[] testButtons;
    private HTextButton menuButton;
    
    // Layout constants
    private static final int BUTTON_WIDTH = 250;
    private static final int BUTTON_HEIGHT = 60;
    private static final int BUTTON_GAP = 30;

    public void initXlet(XletContext context) throws XletStateChangeException {
        System.err.println("StatesTestXlet: initXlet()");
        this.context = context;

        HSceneFactory factory = HSceneFactory.getInstance();
        scene = factory.getDefaultHScene();
        scene.setLayout(null);

        int sceneWidth = scene.getWidth();
        int sceneHeight = scene.getHeight();
        
        Font titleFont = new Font("SansSerif", Font.BOLD, 28);
        Font descFont = new Font("SansSerif", Font.PLAIN, 18);
        Font buttonFont = new Font("SansSerif", Font.BOLD, 22);
        Font menuFont = new Font("SansSerif", Font.PLAIN, 20);
        
        // Title
        titleLabel = new HStaticText("Interaction States Test", 0, 10, sceneWidth, 40);
        titleLabel.setFont(titleFont);
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        titleLabel.setVerticalAlignment(HVisible.VALIGN_CENTER);
        scene.add(titleLabel);
        
        // Description
        descriptionLabel = new HStaticText(
            "Navigate with UP/DOWN arrows. States: NORMAL (gray), FOCUSED (blue), ACTIONED (green when pressed)",
            50, 50, sceneWidth - 100, 40);
        descriptionLabel.setFont(descFont);
        descriptionLabel.setForeground(Color.LIGHT_GRAY);
        descriptionLabel.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        descriptionLabel.setVerticalAlignment(HVisible.VALIGN_CENTER);
        scene.add(descriptionLabel);
        
        // Create test buttons - one is disabled
        String[] labels = { "Button 1 (Normal)", "Button 2 (Normal)", "Button 3 (Disabled)", "Button 4 (Normal)" };
        testButtons = new HTextButton[4];
        
        int startY = 120;
        int buttonX = (sceneWidth - BUTTON_WIDTH) / 2;
        
        for (int i = 0; i < 4; i++) {
            int y = startY + i * (BUTTON_HEIGHT + BUTTON_GAP);
            
            testButtons[i] = new HTextButton(labels[i], buttonX, y, BUTTON_WIDTH, BUTTON_HEIGHT);
            testButtons[i].setFont(buttonFont);
            testButtons[i].setForeground(Color.WHITE);
            testButtons[i].setBackground(new Color(80, 80, 80));
            testButtons[i].setHorizontalAlignment(HVisible.HALIGN_CENTER);
            testButtons[i].setVerticalAlignment(HVisible.VALIGN_CENTER);
            testButtons[i].setActionCommand("button_" + (i + 1));
            testButtons[i].addHActionListener(this);
            
            // Disable button 3
            if (i == 2) {
                testButtons[i].setEnabled(false);
            }
            
            scene.add(testButtons[i]);
        }
        
        // Set up navigation (skip disabled button)
        testButtons[0].setFocusTraversal(testButtons[3], testButtons[1], null, null);
        testButtons[1].setFocusTraversal(testButtons[0], testButtons[3], null, null);  // Skip button 2 (disabled)
        testButtons[3].setFocusTraversal(testButtons[1], testButtons[0], null, null);
        
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
        
        // Add menu button to navigation
        testButtons[3].setFocusTraversal(testButtons[1], menuButton, null, null);
        menuButton.setFocusTraversal(testButtons[3], testButtons[0], null, null);
        
        scene.add(menuButton);

        System.err.println("StatesTestXlet: initXlet() complete");
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
        } else if (command.startsWith("button_")) {
            // Just log button presses - demonstrates ACTIONED state
            System.err.println("StatesTestXlet: " + command + " pressed");
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
