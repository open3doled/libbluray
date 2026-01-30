/*
 * This file is part of libbluray
 * Alignment Test Xlet - tests all 16 horizontal/vertical alignment combinations
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
 * Tests all 16 combinations of horizontal and vertical alignment.
 * 
 * Layout: 4x4 grid where:
 * - Columns represent horizontal alignment: LEFT, CENTER, RIGHT, JUSTIFY
 * - Rows represent vertical alignment: TOP, CENTER, BOTTOM, JUSTIFY
 * 
 * Each cell shows "H/V" indicating its alignment (e.g., "L/T" for LEFT/TOP)
 */
public class AlignmentTestXlet implements Xlet, HActionListener {

    private XletContext context;
    private HScene scene;
    private HStaticText titleLabel;
    private HStaticText[][] alignmentGrid;
    private HTextButton menuButton;
    
    // Alignment constants
    private static final int[] H_ALIGNS = { 
        HVisible.HALIGN_LEFT, 
        HVisible.HALIGN_CENTER, 
        HVisible.HALIGN_RIGHT, 
        HVisible.HALIGN_JUSTIFY 
    };
    private static final String[] H_NAMES = { "L", "C", "R", "J" };
    
    private static final int[] V_ALIGNS = { 
        HVisible.VALIGN_TOP, 
        HVisible.VALIGN_CENTER, 
        HVisible.VALIGN_BOTTOM, 
        HVisible.VALIGN_JUSTIFY 
    };
    private static final String[] V_NAMES = { "T", "C", "B", "J" };
    
    // Layout constants
    private static final int CELL_WIDTH = 200;
    private static final int CELL_HEIGHT = 120;
    private static final int CELL_GAP = 10;
    private static final int MARGIN = 40;

    public void initXlet(XletContext context) throws XletStateChangeException {
        System.err.println("AlignmentTestXlet: initXlet()");
        this.context = context;

        HSceneFactory factory = HSceneFactory.getInstance();
        scene = factory.getDefaultHScene();
        scene.setLayout(null);

        int sceneWidth = scene.getWidth();
        int sceneHeight = scene.getHeight();
        
        Font titleFont = new Font("SansSerif", Font.BOLD, 28);
        Font cellFont = new Font("SansSerif", Font.BOLD, 24);
        Font buttonFont = new Font("SansSerif", Font.PLAIN, 20);
        
        // Title
        titleLabel = new HStaticText("Alignment Test (H: L/C/R/J, V: T/C/B/J)", 0, 10, sceneWidth, 40);
        titleLabel.setFont(titleFont);
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        titleLabel.setVerticalAlignment(HVisible.VALIGN_CENTER);
        scene.add(titleLabel);
        
        // Calculate grid position (centered)
        int gridWidth = 4 * CELL_WIDTH + 3 * CELL_GAP;
        int gridHeight = 4 * CELL_HEIGHT + 3 * CELL_GAP;
        int gridX = (sceneWidth - gridWidth) / 2;
        int gridY = 60;
        
        // Create 4x4 alignment grid
        alignmentGrid = new HStaticText[4][4];
        
        // Column headers (horizontal alignment labels)
        String[] hHeaders = { "LEFT", "CENTER", "RIGHT", "JUSTIFY" };
        for (int col = 0; col < 4; col++) {
            // Header labels are handled in the cells themselves
        }
        
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int x = gridX + col * (CELL_WIDTH + CELL_GAP);
                int y = gridY + row * (CELL_HEIGHT + CELL_GAP);
                
                // Cell text shows alignment codes
                String text = H_NAMES[col] + "/" + V_NAMES[row];
                
                alignmentGrid[row][col] = new HStaticText(text, x, y, CELL_WIDTH, CELL_HEIGHT);
                alignmentGrid[row][col].setFont(cellFont);
                alignmentGrid[row][col].setForeground(Color.WHITE);
                alignmentGrid[row][col].setBackground(new Color(40, 40, 80));
                alignmentGrid[row][col].setBackgroundMode(HVisible.BACKGROUND_FILL);
                
                // Set alignment for this cell
                alignmentGrid[row][col].setHorizontalAlignment(H_ALIGNS[col]);
                alignmentGrid[row][col].setVerticalAlignment(V_ALIGNS[row]);
                
                scene.add(alignmentGrid[row][col]);
            }
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

        System.err.println("AlignmentTestXlet: initXlet() complete");
    }

    public void startXlet() throws XletStateChangeException {
        System.err.println("AlignmentTestXlet: startXlet()");
        scene.setVisible(true);
        menuButton.requestFocus();
        scene.repaint();
        System.err.println("AlignmentTestXlet: Press ENTER on Menu button to return to menu");
    }

    public void pauseXlet() {
        System.err.println("AlignmentTestXlet: pauseXlet()");
    }

    public void destroyXlet(boolean force) throws XletStateChangeException {
        System.err.println("AlignmentTestXlet: destroyXlet()");
        if (scene != null) {
            scene.setVisible(false);
            scene.removeAll();
            scene.dispose();
            scene = null;
        }
    }

    public void actionPerformed(ActionEvent e) {
        String command = e.getActionCommand();
        System.err.println("AlignmentTestXlet: actionPerformed(" + command + ")");
        
        if ("menu".equals(command)) {
            returnToMenu();
        }
    }
    
    private void returnToMenu() {
        try {
            System.err.println("AlignmentTestXlet: Returning to menu (title 0)");
            BDLocator locator = new BDLocator(null, 0, -1);
            Title title = (Title) SIManager.createInstance().getService(locator);
            if (title != null) {
                TitleContext titleContext = (TitleContext) ServiceContextFactory.getInstance().getServiceContext(null);
                if (titleContext != null) {
                    titleContext.start(title, true);
                }
            }
        } catch (Exception ex) {
            System.err.println("AlignmentTestXlet: Error returning to menu: " + ex.getMessage());
            ex.printStackTrace();
        }
    }
}
