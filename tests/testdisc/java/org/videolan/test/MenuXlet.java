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
    private HTextButton[][] menuGrid;  // 2D grid for navigation
    
    // Menu configuration: 4 rows x 2 columns
    // Grid layout:  [Alignment]    [Background/Border]
    //               [States]       [Font]
    //               [Multiline]    [Resize]
    //               [Color]        [empty]
    private static final String[][] MENU_LABELS = {
        { "Alignment", "Background/Border" },
        { "States", "Font" },
        { "Multiline", "Resize" },
        { "Color", null }  // null = empty cell
    };
    
    // Title numbers corresponding to each menu item (row-major order)
    private static final int[][] TITLE_NUMBERS = {
        { 1, 2 },
        { 3, 4 },
        { 5, 6 },
        { 7, -1 }  // -1 = no button
    };
    
    // Layout constants
    private static final int BUTTON_WIDTH = 280;
    private static final int BUTTON_HEIGHT = 55;
    private static final int BUTTON_GAP_H = 40;
    private static final int BUTTON_GAP_V = 20;
    private static final int MARGIN = 50;
    
    private static final int ROWS = 4;
    private static final int COLS = 2;

    public void initXlet(XletContext context) throws XletStateChangeException {
        System.err.println("MenuXlet: initXlet()");
        this.context = context;

        // Get the default HScene
        HSceneFactory factory = HSceneFactory.getInstance();
        scene = factory.getDefaultHScene();
        scene.setLayout(null);

        int sceneWidth = scene.getWidth();
        int sceneHeight = scene.getHeight();
        
        // Calculate centered position for menu grid
        int gridWidth = COLS * BUTTON_WIDTH + (COLS - 1) * BUTTON_GAP_H;
        int gridX = (sceneWidth - gridWidth) / 2;
        int menuStartY = MARGIN + 100;  // Leave room for title
        
        // Title font
        Font titleFont = new Font("SansSerif", Font.BOLD, 36);
        Font subtitleFont = new Font("SansSerif", Font.PLAIN, 18);
        Font buttonFont = new Font("SansSerif", Font.BOLD, 22);
        
        // Create title label
        titleLabel = new HStaticText("HAVI Rendering Tests", 0, MARGIN, sceneWidth, 50);
        titleLabel.setFont(titleFont);
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        titleLabel.setVerticalAlignment(HVisible.VALIGN_CENTER);
        scene.add(titleLabel);
        
        // Subtitle with navigation hint
        HStaticText subtitle = new HStaticText("Use arrows to navigate, ENTER to select", 0, MARGIN + 50, sceneWidth, 30);
        subtitle.setFont(subtitleFont);
        subtitle.setForeground(Color.LIGHT_GRAY);
        subtitle.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        subtitle.setVerticalAlignment(HVisible.VALIGN_CENTER);
        scene.add(subtitle);
        
        // Create menu button grid
        menuGrid = new HTextButton[ROWS][COLS];
        
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                if (MENU_LABELS[row][col] == null) {
                    continue;  // Skip empty cells
                }
                
                int buttonX = gridX + col * (BUTTON_WIDTH + BUTTON_GAP_H);
                int buttonY = menuStartY + row * (BUTTON_HEIGHT + BUTTON_GAP_V);
                
                menuGrid[row][col] = new HTextButton(MENU_LABELS[row][col], buttonX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT);
                menuGrid[row][col].setFont(buttonFont);
                menuGrid[row][col].setForeground(Color.WHITE);
                menuGrid[row][col].setBackground(new Color(60, 60, 60));
                menuGrid[row][col].setHorizontalAlignment(HVisible.HALIGN_CENTER);
                menuGrid[row][col].setVerticalAlignment(HVisible.VALIGN_CENTER);
                menuGrid[row][col].setActionCommand("title_" + TITLE_NUMBERS[row][col]);
                menuGrid[row][col].addHActionListener(this);
                scene.add(menuGrid[row][col]);
            }
        }
        
        // Set up 2D navigation between buttons
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                if (menuGrid[row][col] == null) {
                    continue;
                }
                
                // Find neighbors (with wrapping)
                HTextButton up = findButton(row - 1, col, -1, 0);     // Search up
                HTextButton down = findButton(row + 1, col, 1, 0);   // Search down
                HTextButton left = findButton(row, col - 1, 0, -1);  // Search left
                HTextButton right = findButton(row, col + 1, 0, 1);  // Search right
                
                // setFocusTraversal(up, down, left, right)
                menuGrid[row][col].setFocusTraversal(up, down, left, right);
            }
        }

        System.err.println("MenuXlet: initXlet() complete");
        System.err.println("  Scene size: " + sceneWidth + "x" + sceneHeight);
        System.err.println("  Grid: " + ROWS + "x" + COLS);
    }
    
    /**
     * Find the next button in a direction, wrapping around if needed.
     */
    private HTextButton findButton(int startRow, int startCol, int rowDir, int colDir) {
        int row = startRow;
        int col = startCol;
        
        // Try up to ROWS * COLS times to find a button
        for (int i = 0; i < ROWS * COLS; i++) {
            // Wrap coordinates
            row = (row + ROWS) % ROWS;
            col = (col + COLS) % COLS;
            
            if (menuGrid[row][col] != null) {
                return menuGrid[row][col];
            }
            
            // Move to next position
            row += rowDir;
            col += colDir;
        }
        
        return null;  // No button found
    }

    public void startXlet() throws XletStateChangeException {
        System.err.println("MenuXlet: startXlet()");
        
        scene.setVisible(true);
        
        // Focus first menu item (top-left)
        if (menuGrid[0][0] != null) {
            menuGrid[0][0].requestFocus();
        }
        
        scene.repaint();
        
        System.err.println("MenuXlet: startXlet() complete");
        System.err.println("  Use arrow keys to navigate (UP/DOWN/LEFT/RIGHT)");
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
