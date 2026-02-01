/*
 * This file is part of libbluray
 * Color Test Xlet - tests foreground/background colors and alpha transparency
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
 * Tests color rendering including foreground, background, and alpha transparency.
 * 
 * Shows:
 * - Various foreground text colors
 * - Various background colors
 * - Alpha transparency levels
 */
public class ColorTestXlet implements Xlet, HActionListener {

    private XletContext context;
    private HScene scene;
    private HStaticText titleLabel;
    private HTextButton menuButton;
    
    // Layout constants
    private static final int BOX_WIDTH = 180;
    private static final int BOX_HEIGHT = 50;
    private static final int BOX_GAP = 15;

    public void initXlet(XletContext context) throws XletStateChangeException {
        System.err.println("ColorTestXlet: initXlet()");
        this.context = context;

        HSceneFactory factory = HSceneFactory.getInstance();
        scene = factory.getDefaultHScene();
        scene.setLayout(null);

        int sceneWidth = scene.getWidth();
        int sceneHeight = scene.getHeight();
        
        Font titleFont = new Font("SansSerif", Font.BOLD, 28);
        Font sectionFont = new Font("SansSerif", Font.BOLD, 18);
        Font textFont = new Font("SansSerif", Font.BOLD, 16);
        Font menuFont = new Font("SansSerif", Font.PLAIN, 20);
        
        // Title
        titleLabel = new HStaticText("Color & Transparency Test", 0, 5, sceneWidth, 35);
        titleLabel.setFont(titleFont);
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        titleLabel.setVerticalAlignment(HVisible.VALIGN_CENTER);
        scene.add(titleLabel);
        
        int y = 45;
        int leftX = 30;
        int rightX = sceneWidth / 2 + 30;
        
        // Section 1: Foreground colors (left column)
        HStaticText fgSection = new HStaticText("Foreground Colors:", leftX, y, 300, 25);
        fgSection.setFont(sectionFont);
        fgSection.setForeground(Color.YELLOW);
        scene.add(fgSection);
        y += 30;
        
        Color[] fgColors = { Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA, Color.ORANGE };
        String[] fgNames = { "Red", "Green", "Blue", "Cyan", "Magenta", "Orange" };
        
        for (int i = 0; i < fgColors.length; i++) {
            HStaticText text = new HStaticText(fgNames[i] + " Text", leftX, y, BOX_WIDTH, BOX_HEIGHT);
            text.setFont(textFont);
            text.setForeground(fgColors[i]);
            text.setBackground(new Color(40, 40, 40));
            text.setBackgroundMode(HVisible.BACKGROUND_FILL);
            text.setHorizontalAlignment(HVisible.HALIGN_CENTER);
            text.setVerticalAlignment(HVisible.VALIGN_CENTER);
            scene.add(text);
            y += BOX_HEIGHT + BOX_GAP;
        }
        
        // Section 2: Background colors (right column, starting from top)
        y = 45;
        HStaticText bgSection = new HStaticText("Background Colors:", rightX, y, 300, 25);
        bgSection.setFont(sectionFont);
        bgSection.setForeground(Color.YELLOW);
        scene.add(bgSection);
        y += 30;
        
        Color[] bgColors = { Color.RED, Color.GREEN, Color.BLUE, new Color(128, 0, 128), new Color(0, 128, 128), Color.DARK_GRAY };
        String[] bgNames = { "Red BG", "Green BG", "Blue BG", "Purple BG", "Teal BG", "Dark Gray BG" };
        
        for (int i = 0; i < bgColors.length; i++) {
            HStaticText text = new HStaticText(bgNames[i], rightX, y, BOX_WIDTH, BOX_HEIGHT);
            text.setFont(textFont);
            text.setForeground(Color.WHITE);
            text.setBackground(bgColors[i]);
            text.setBackgroundMode(HVisible.BACKGROUND_FILL);
            text.setHorizontalAlignment(HVisible.HALIGN_CENTER);
            text.setVerticalAlignment(HVisible.VALIGN_CENTER);
            scene.add(text);
            y += BOX_HEIGHT + BOX_GAP;
        }
        
        // Section 3: Alpha transparency (bottom section)
        y = Math.max(y, 45 + 30 + 6 * (BOX_HEIGHT + BOX_GAP)) + 10;
        
        HStaticText alphaSection = new HStaticText("Alpha Transparency (on striped background):", leftX, y, sceneWidth - 60, 25);
        alphaSection.setFont(sectionFont);
        alphaSection.setForeground(Color.YELLOW);
        scene.add(alphaSection);
        y += 30;
        
        // Create striped background for alpha test
        int stripeWidth = sceneWidth - 60;
        int stripeHeight = 80;
        HStaticText stripesBg = new HStaticText("", leftX, y, stripeWidth, stripeHeight);
        // Use a simple dark background - stripes would require custom painting
        stripesBg.setBackground(new Color(100, 100, 100));
        stripesBg.setBackgroundMode(HVisible.BACKGROUND_FILL);
        scene.add(stripesBg);
        
        // Alpha test boxes on top of stripe background
        int[] alphas = { 255, 192, 128, 64 };
        String[] alphaLabels = { "100%", "75%", "50%", "25%" };
        int alphaBoxWidth = 150;
        int alphaBoxGap = 20;
        int alphaStartX = leftX + (stripeWidth - 4 * alphaBoxWidth - 3 * alphaBoxGap) / 2;
        
        for (int i = 0; i < alphas.length; i++) {
            int x = alphaStartX + i * (alphaBoxWidth + alphaBoxGap);
            Color alphaColor = new Color(0, 0, 255, alphas[i]);  // Blue with varying alpha
            
            HStaticText alphaBox = new HStaticText(alphaLabels[i], x, y + 15, alphaBoxWidth, 50);
            alphaBox.setFont(textFont);
            alphaBox.setForeground(Color.WHITE);
            alphaBox.setBackground(alphaColor);
            alphaBox.setBackgroundMode(HVisible.BACKGROUND_FILL);
            alphaBox.setHorizontalAlignment(HVisible.HALIGN_CENTER);
            alphaBox.setVerticalAlignment(HVisible.VALIGN_CENTER);
            scene.add(alphaBox);
        }
        
        // Menu button at bottom
        int buttonWidth = 150;
        int buttonHeight = 40;
        menuButton = new HTextButton("Menu", 
            (sceneWidth - buttonWidth) / 2, 
            sceneHeight - buttonHeight - 15,
            buttonWidth, buttonHeight);
        menuButton.setFont(menuFont);
        menuButton.setForeground(Color.WHITE);
        menuButton.setBackground(new Color(80, 80, 80));
        menuButton.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        menuButton.setVerticalAlignment(HVisible.VALIGN_CENTER);
        menuButton.setActionCommand("menu");
        menuButton.addHActionListener(this);
        scene.add(menuButton);

        System.err.println("ColorTestXlet: initXlet() complete");
    }

    public void startXlet() throws XletStateChangeException {
        System.err.println("ColorTestXlet: startXlet()");
        scene.setVisible(true);
        menuButton.requestFocus();
        scene.repaint();
    }

    public void pauseXlet() {
        System.err.println("ColorTestXlet: pauseXlet()");
    }

    public void destroyXlet(boolean force) throws XletStateChangeException {
        System.err.println("ColorTestXlet: destroyXlet()");
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
            System.err.println("ColorTestXlet: Error returning to menu: " + ex.getMessage());
        }
    }
}
