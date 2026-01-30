/*
 * This file is part of libbluray
 * Menu Xlet for selecting HAVI rendering tests
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
 * Main menu Xlet for the HAVI rendering test disc.
 * 
 * Displays a list of test xlets that can be selected using arrow keys and ENTER.
 * Each test is launched by switching to its corresponding title.
 * 
 * Return to this menu via:
 * - Top Menu key (configured in index.bdmv)
 * - Popup Menu key (configured in index.bdmv)
 */
public class MenuXlet implements Xlet, HActionListener {

    private XletContext context;
    private HScene scene;
    
    // Menu items
    private HStaticText titleLabel;
    private HTextButton[] menuButtons;
    
    // Menu configuration: button label -> title number
    private static final String[] MENU_LABELS = {
        "1. Alignment Test",
        "2. Background/Border Test",
        "3. States Test",
        "4. Font Test",
        "5. Multiline Text Test",
        "6. Resize Test",
        "7. Color Test",
        "8. HAVI Test (Original)"
    };
    
    // Title numbers corresponding to each menu item (titles are 0-indexed, menu is title 0)
    private static final int[] TITLE_NUMBERS = { 1, 2, 3, 4, 5, 6, 7, 8 };
    
    // Layout constants
    private static final int BUTTON_WIDTH = 400;
    private static final int BUTTON_HEIGHT = 45;
    private static final int BUTTON_GAP = 10;
    private static final int MARGIN = 50;

    public void initXlet(XletContext context) throws XletStateChangeException {
        System.err.println("MenuXlet: initXlet()");
        this.context = context;

        // Get the default HScene
        HSceneFactory factory = HSceneFactory.getInstance();
        scene = factory.getDefaultHScene();
        scene.setLayout(null);

        int sceneWidth = scene.getWidth();
        int sceneHeight = scene.getHeight();
        
        // Calculate centered position for menu
        int menuX = (sceneWidth - BUTTON_WIDTH) / 2;
        int menuStartY = MARGIN + 80;  // Leave room for title
        
        // Title font
        Font titleFont = new Font("SansSerif", Font.BOLD, 36);
        Font buttonFont = new Font("SansSerif", Font.PLAIN, 24);
        
        // Create title label
        titleLabel = new HStaticText("HAVI Rendering Tests", 0, MARGIN, sceneWidth, 60);
        titleLabel.setFont(titleFont);
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        titleLabel.setVerticalAlignment(HVisible.VALIGN_CENTER);
        
        // Create menu buttons
        menuButtons = new HTextButton[MENU_LABELS.length];
        for (int i = 0; i < MENU_LABELS.length; i++) {
            int buttonY = menuStartY + i * (BUTTON_HEIGHT + BUTTON_GAP);
            
            menuButtons[i] = new HTextButton(MENU_LABELS[i], menuX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT);
            menuButtons[i].setFont(buttonFont);
            menuButtons[i].setForeground(Color.WHITE);
            menuButtons[i].setBackground(new Color(60, 60, 60));
            menuButtons[i].setHorizontalAlignment(HVisible.HALIGN_LEFT);
            menuButtons[i].setVerticalAlignment(HVisible.VALIGN_CENTER);
            menuButtons[i].setActionCommand("title_" + TITLE_NUMBERS[i]);
            menuButtons[i].addHActionListener(this);
        }
        
        // Set up vertical navigation between buttons
        for (int i = 0; i < menuButtons.length; i++) {
            HTextButton up = menuButtons[(i - 1 + menuButtons.length) % menuButtons.length];
            HTextButton down = menuButtons[(i + 1) % menuButtons.length];
            // setFocusTraversal(up, down, left, right)
            menuButtons[i].setFocusTraversal(up, down, null, null);
        }
        
        // Add components to scene
        scene.add(titleLabel);
        for (HTextButton button : menuButtons) {
            scene.add(button);
        }

        System.err.println("MenuXlet: initXlet() complete");
        System.err.println("  Scene size: " + sceneWidth + "x" + sceneHeight);
        System.err.println("  Menu items: " + MENU_LABELS.length);
    }

    public void startXlet() throws XletStateChangeException {
        System.err.println("MenuXlet: startXlet()");
        
        scene.setVisible(true);
        
        // Focus first menu item
        if (menuButtons.length > 0) {
            menuButtons[0].requestFocus();
        }
        
        scene.repaint();
        
        System.err.println("MenuXlet: startXlet() complete");
        System.err.println("  Use UP/DOWN arrows to navigate");
        System.err.println("  Press ENTER to select a test");
    }

    public void pauseXlet() {
        System.err.println("MenuXlet: pauseXlet()");
    }

    public void destroyXlet(boolean force) throws XletStateChangeException {
        System.err.println("MenuXlet: destroyXlet()");
        if (scene != null) {
            scene.setVisible(false);
            scene.removeAll();
            scene.dispose();
            scene = null;
        }
    }

    /**
     * Handle menu button selection - switch to the selected test title
     */
    public void actionPerformed(ActionEvent e) {
        String command = e.getActionCommand();
        System.err.println("MenuXlet: actionPerformed(" + command + ")");
        
        if (command != null && command.startsWith("title_")) {
            try {
                int titleNum = Integer.parseInt(command.substring(6));
                System.err.println("MenuXlet: Switching to title " + titleNum);
                
                // Create a locator for the title
                BDLocator locator = new BDLocator(null, titleNum, -1);
                
                // Get the Title service from the SIManager
                Title title = (Title) SIManager.createInstance().getService(locator);
                if (title == null) {
                    System.err.println("MenuXlet: ERROR - Title " + titleNum + " not found");
                    return;
                }
                
                // Get the TitleContext and start the new title
                TitleContext titleContext = (TitleContext) ServiceContextFactory.getInstance().getServiceContext(null);
                if (titleContext != null) {
                    titleContext.start(title, true);
                } else {
                    System.err.println("MenuXlet: ERROR - Could not get TitleContext");
                }
            } catch (Exception ex) {
                System.err.println("MenuXlet: Error switching title: " + ex.getMessage());
                ex.printStackTrace();
            }
        }
    }
}
