/*
 * This file is part of libbluray
 * Multiline Text Test Xlet - tests multi-line text rendering
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
 * Tests multi-line text rendering with different alignments.
 * 
 * Shows boxes with multi-line text using various horizontal and vertical
 * alignment combinations.
 */
public class MultilineTextXlet implements Xlet, HActionListener {

    private XletContext context;
    private HScene scene;
    private HStaticText titleLabel;
    private HTextButton menuButton;
    
    // Layout constants
    private static final int BOX_WIDTH = 280;
    private static final int BOX_HEIGHT = 150;
    private static final int BOX_GAP = 30;

    public void initXlet(XletContext context) throws XletStateChangeException {
        System.err.println("MultilineTextXlet: initXlet()");
        this.context = context;

        HSceneFactory factory = HSceneFactory.getInstance();
        scene = factory.getDefaultHScene();
        scene.setLayout(null);

        int sceneWidth = scene.getWidth();
        int sceneHeight = scene.getHeight();
        
        Font titleFont = new Font("SansSerif", Font.BOLD, 28);
        Font textFont = new Font("SansSerif", Font.PLAIN, 18);
        Font labelFont = new Font("SansSerif", Font.PLAIN, 14);
        Font menuFont = new Font("SansSerif", Font.PLAIN, 20);
        
        // Title
        titleLabel = new HStaticText("Multiline Text Test", 0, 10, sceneWidth, 40);
        titleLabel.setFont(titleFont);
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        titleLabel.setVerticalAlignment(HVisible.VALIGN_CENTER);
        scene.add(titleLabel);
        
        // Multi-line text content
        String multilineText = "Line One\nLine Two\nLine Three";
        
        // Test configurations: horizontal alignment varies across columns
        int[] hAligns = { HVisible.HALIGN_LEFT, HVisible.HALIGN_CENTER, HVisible.HALIGN_RIGHT };
        String[] hNames = { "LEFT", "CENTER", "RIGHT" };
        
        // Vertical alignment varies across rows
        int[] vAligns = { HVisible.VALIGN_TOP, HVisible.VALIGN_CENTER, HVisible.VALIGN_BOTTOM };
        String[] vNames = { "TOP", "CENTER", "BOTTOM" };
        
        // Calculate grid position
        int gridWidth = 3 * BOX_WIDTH + 2 * BOX_GAP;
        int gridX = (sceneWidth - gridWidth) / 2;
        int startY = 70;
        
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int x = gridX + col * (BOX_WIDTH + BOX_GAP);
                int y = startY + row * (BOX_HEIGHT + BOX_GAP + 20);
                
                // Label above box
                String labelText = hNames[col] + "/" + vNames[row];
                HStaticText label = new HStaticText(labelText, x, y, BOX_WIDTH, 18);
                label.setFont(labelFont);
                label.setForeground(Color.YELLOW);
                label.setHorizontalAlignment(HVisible.HALIGN_CENTER);
                scene.add(label);
                
                // Text box
                HStaticText textBox = new HStaticText(multilineText, x, y + 20, BOX_WIDTH, BOX_HEIGHT);
                textBox.setFont(textFont);
                textBox.setForeground(Color.WHITE);
                textBox.setBackground(new Color(50, 50, 80));
                textBox.setBackgroundMode(HVisible.BACKGROUND_FILL);
                textBox.setHorizontalAlignment(hAligns[col]);
                textBox.setVerticalAlignment(vAligns[row]);
                scene.add(textBox);
            }
        }
        
        // Menu button at bottom
        int buttonWidth = 150;
        int buttonHeight = 40;
        menuButton = new HTextButton("Menu", 
            (sceneWidth - buttonWidth) / 2, 
            sceneHeight - buttonHeight - 10,
            buttonWidth, buttonHeight);
        menuButton.setFont(menuFont);
        menuButton.setForeground(Color.WHITE);
        menuButton.setBackground(new Color(80, 80, 80));
        menuButton.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        menuButton.setVerticalAlignment(HVisible.VALIGN_CENTER);
        menuButton.setActionCommand("menu");
        menuButton.addHActionListener(this);
        scene.add(menuButton);

        System.err.println("MultilineTextXlet: initXlet() complete");
    }

    public void startXlet() throws XletStateChangeException {
        System.err.println("MultilineTextXlet: startXlet()");
        scene.setVisible(true);
        menuButton.requestFocus();
        scene.repaint();
    }

    public void pauseXlet() {
        System.err.println("MultilineTextXlet: pauseXlet()");
    }

    public void destroyXlet(boolean force) throws XletStateChangeException {
        System.err.println("MultilineTextXlet: destroyXlet()");
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
            System.err.println("MultilineTextXlet: Error returning to menu: " + ex.getMessage());
        }
    }
}
