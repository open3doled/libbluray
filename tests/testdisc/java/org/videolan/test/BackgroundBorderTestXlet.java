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
import org.havi.ui.HText;
import org.havi.ui.HTextButton;
import org.havi.ui.HVisible;
import org.havi.ui.event.HActionListener;

/**
 * Tests background fill modes and border rendering.
 * 
 * Layout: 2x2 grid showing combinations of:
 * - NO_BACKGROUND_FILL vs BACKGROUND_FILL
 * - Borders disabled (never shown) vs enabled (shown when focused)
 * 
 * Navigate between cells to see borders appear/disappear on focus.
 * Text changes to indicate focus state.
 */
public class BackgroundBorderTestXlet implements Xlet, HActionListener {

    private XletContext context;
    private HScene scene;
    private HStaticText titleLabel;
    private HStaticText subtitleLabel;
    private HText[] testCells;
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
        Font subtitleFont = new Font("SansSerif", Font.PLAIN, 16);
        Font cellFont = new Font("SansSerif", Font.PLAIN, 18);
        Font buttonFont = new Font("SansSerif", Font.PLAIN, 20);
        
        // Title
        titleLabel = new HStaticText("Background Fill & Border Test", 0, 10, sceneWidth, 35);
        titleLabel.setFont(titleFont);
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        titleLabel.setVerticalAlignment(HVisible.VALIGN_CENTER);
        scene.add(titleLabel);
        
        // Subtitle with instructions
        subtitleLabel = new HStaticText("Navigate with arrows. Borders appear when cell is FOCUSED.", 0, 45, sceneWidth, 25);
        subtitleLabel.setFont(subtitleFont);
        subtitleLabel.setForeground(Color.LIGHT_GRAY);
        subtitleLabel.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        subtitleLabel.setVerticalAlignment(HVisible.VALIGN_CENTER);
        scene.add(subtitleLabel);
        
        // Calculate grid position (centered)
        int gridWidth = 2 * CELL_WIDTH + CELL_GAP;
        int gridX = (sceneWidth - gridWidth) / 2;
        int gridY = 90;
        
        // Test configurations - text changes based on focus state
        // Format: { normalText, focusedText }
        String[][] labels = {
            { "NO_BG_FILL\nNo Border", "NO_BG_FILL\nNo Border\n[FOCUSED]" },
            { "NO_BG_FILL\nBorder when focused", "NO_BG_FILL\nBorder when focused\n[FOCUSED]" },
            { "BG_FILL\nNo Border", "BG_FILL\nNo Border\n[FOCUSED]" },
            { "BG_FILL\nBorder when focused", "BG_FILL\nBorder when focused\n[FOCUSED]" }
        };
        
        boolean[] bgFill = { false, false, true, true };
        boolean[] borders = { false, true, false, true };
        
        testCells = new HText[4];
        
        for (int i = 0; i < 4; i++) {
            int row = i / 2;
            int col = i % 2;
            int x = gridX + col * (CELL_WIDTH + CELL_GAP);
            int y = gridY + row * (CELL_HEIGHT + CELL_GAP);
            
            // HText constructor: (normalText, focusedText, x, y, width, height)
            testCells[i] = new HText(labels[i][0], labels[i][1], x, y, CELL_WIDTH, CELL_HEIGHT);
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
            
            // Set borders - when enabled, border shows on focus
            testCells[i].setBordersEnabled(borders[i]);
            
            scene.add(testCells[i]);
        }
        
        // Set up 2x2 grid navigation between test cells
        // Layout:  [0] [1]
        //          [2] [3]
        testCells[0].setFocusTraversal(testCells[2], testCells[2], testCells[1], testCells[1]);
        testCells[1].setFocusTraversal(testCells[3], testCells[3], testCells[0], testCells[0]);
        testCells[2].setFocusTraversal(testCells[0], testCells[0], testCells[3], testCells[3]);
        testCells[3].setFocusTraversal(testCells[1], testCells[1], testCells[2], testCells[2]);
        
        // Menu button at bottom (outside the test grid)
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
        
        // Navigation: down from bottom row goes to menu, up from menu goes to bottom row
        testCells[2].setFocusTraversal(testCells[0], menuButton, testCells[3], testCells[3]);
        testCells[3].setFocusTraversal(testCells[1], menuButton, testCells[2], testCells[2]);
        menuButton.setFocusTraversal(testCells[2], testCells[0], null, null);
        
        scene.add(menuButton);

        System.err.println("BackgroundBorderTestXlet: initXlet() complete");
    }

    public void startXlet() throws XletStateChangeException {
        System.err.println("BackgroundBorderTestXlet: startXlet()");
        scene.setVisible(true);
        // Focus first test cell to immediately show border behavior
        testCells[0].requestFocus();
        scene.repaint();
        System.err.println("BackgroundBorderTestXlet: Navigate between cells to see borders appear/disappear");
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
