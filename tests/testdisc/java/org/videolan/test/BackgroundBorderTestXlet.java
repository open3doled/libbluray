/*
 * This file is part of libbluray
 * Background/Border Test Xlet - tests BACKGROUND_FILL modes and borders
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
 * Tests background fill modes and border rendering.
 * 
 * Layout: 2x2 grid showing:
 * - NO_BACKGROUND_FILL + borders disabled
 * - NO_BACKGROUND_FILL + borders enabled
 * - BACKGROUND_FILL + borders disabled
 * - BACKGROUND_FILL + borders enabled
 */
public class BackgroundBorderTestXlet implements Xlet, HActionListener {

    private XletContext context;
    private HScene scene;
    private HStaticText titleLabel;
    private HStaticText[] testCells;
    private HTextButton menuButton;
    
    // Layout constants
    private static final int CELL_WIDTH = 350;
    private static final int CELL_HEIGHT = 180;
    private static final int CELL_GAP = 40;
    private static final int MARGIN = 40;

    public void initXlet(XletContext context) throws XletStateChangeException {
        System.err.println("BackgroundBorderTestXlet: initXlet()");
        this.context = context;

        HSceneFactory factory = HSceneFactory.getInstance();
        scene = factory.getDefaultHScene();
        scene.setLayout(null);

        int sceneWidth = scene.getWidth();
        int sceneHeight = scene.getHeight();
        
        Font titleFont = new Font("SansSerif", Font.BOLD, 28);
        Font cellFont = new Font("SansSerif", Font.PLAIN, 20);
        Font buttonFont = new Font("SansSerif", Font.PLAIN, 20);
        
        // Title
        titleLabel = new HStaticText("Background Fill & Border Test", 0, 10, sceneWidth, 40);
        titleLabel.setFont(titleFont);
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        titleLabel.setVerticalAlignment(HVisible.VALIGN_CENTER);
        scene.add(titleLabel);
        
        // Calculate grid position (centered)
        int gridWidth = 2 * CELL_WIDTH + CELL_GAP;
        int gridHeight = 2 * CELL_HEIGHT + CELL_GAP;
        int gridX = (sceneWidth - gridWidth) / 2;
        int gridY = 80;
        
        // Test configurations
        String[] labels = {
            "NO_BACKGROUND_FILL\nBorders: OFF",
            "NO_BACKGROUND_FILL\nBorders: ON",
            "BACKGROUND_FILL\nBorders: OFF",
            "BACKGROUND_FILL\nBorders: ON"
        };
        
        boolean[] bgFill = { false, false, true, true };
        boolean[] borders = { false, true, false, true };
        
        testCells = new HStaticText[4];
        
        for (int i = 0; i < 4; i++) {
            int row = i / 2;
            int col = i % 2;
            int x = gridX + col * (CELL_WIDTH + CELL_GAP);
            int y = gridY + row * (CELL_HEIGHT + CELL_GAP);
            
            testCells[i] = new HStaticText(labels[i], x, y, CELL_WIDTH, CELL_HEIGHT);
            testCells[i].setFont(cellFont);
            testCells[i].setForeground(Color.WHITE);
            testCells[i].setBackground(new Color(100, 50, 50));
            testCells[i].setHorizontalAlignment(HVisible.HALIGN_CENTER);
            testCells[i].setVerticalAlignment(HVisible.VALIGN_CENTER);
            
            // Set background mode
            if (bgFill[i]) {
                testCells[i].setBackgroundMode(HVisible.BACKGROUND_FILL);
            } else {
                testCells[i].setBackgroundMode(HVisible.NO_BACKGROUND_FILL);
            }
            
            // Set borders
            testCells[i].setBordersEnabled(borders[i]);
            
            scene.add(testCells[i]);
        }
        
        // Menu button at bottom
        int buttonWidth = 150;
        int buttonHeight = 40;
        menuButton = new HTextButton("Menu", 
            (sceneWidth - buttonWidth) / 2, 
            sceneHeight - buttonHeight - 20,
            buttonWidth, buttonHeight);
        menuButton.setFont(buttonFont);
        menuButton.setForeground(Color.WHITE);
        menuButton.setBackground(new Color(80, 80, 80));
        menuButton.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        menuButton.setVerticalAlignment(HVisible.VALIGN_CENTER);
        menuButton.setActionCommand("menu");
        menuButton.addHActionListener(this);
        scene.add(menuButton);

        System.err.println("BackgroundBorderTestXlet: initXlet() complete");
    }

    public void startXlet() throws XletStateChangeException {
        System.err.println("BackgroundBorderTestXlet: startXlet()");
        scene.setVisible(true);
        menuButton.requestFocus();
        scene.repaint();
    }

    public void pauseXlet() {
        System.err.println("BackgroundBorderTestXlet: pauseXlet()");
    }

    public void destroyXlet(boolean force) throws XletStateChangeException {
        System.err.println("BackgroundBorderTestXlet: destroyXlet()");
        if (scene != null) {
            scene.setVisible(false);
            scene.removeAll();
            scene.dispose();
            scene = null;
        }
    }

    public void actionPerformed(ActionEvent e) {
        if ("menu".equals(e.getActionCommand())) {
            returnToMenu();
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
            System.err.println("BackgroundBorderTestXlet: Error returning to menu: " + ex.getMessage());
        }
    }
}
