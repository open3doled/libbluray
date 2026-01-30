/*
 * This file is part of libbluray
 * Resize Test Xlet - tests resize modes for graphics
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 */

package org.videolan.test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.awt.image.MemoryImageSource;

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
import org.havi.ui.HStaticIcon;
import org.havi.ui.HStaticText;
import org.havi.ui.HTextButton;
import org.havi.ui.HVisible;
import org.havi.ui.event.HActionListener;

/**
 * Tests resize modes for image rendering.
 * 
 * Shows the same source image with different resize modes:
 * - RESIZE_NONE: Image displayed at original size
 * - RESIZE_PRESERVE_ASPECT: Image scaled to fit, maintaining aspect ratio
 * - RESIZE_ARBITRARY: Image stretched to fill container
 */
public class ResizeTestXlet implements Xlet, HActionListener {

    private XletContext context;
    private HScene scene;
    private HStaticText titleLabel;
    private HTextButton menuButton;
    
    // Layout constants
    private static final int BOX_WIDTH = 250;
    private static final int BOX_HEIGHT = 200;
    private static final int BOX_GAP = 40;
    
    // Source image size (different aspect ratio than containers)
    private static final int IMG_WIDTH = 80;
    private static final int IMG_HEIGHT = 60;

    public void initXlet(XletContext context) throws XletStateChangeException {
        System.err.println("ResizeTestXlet: initXlet()");
        this.context = context;

        HSceneFactory factory = HSceneFactory.getInstance();
        scene = factory.getDefaultHScene();
        scene.setLayout(null);

        int sceneWidth = scene.getWidth();
        int sceneHeight = scene.getHeight();
        
        Font titleFont = new Font("SansSerif", Font.BOLD, 28);
        Font labelFont = new Font("SansSerif", Font.PLAIN, 16);
        Font menuFont = new Font("SansSerif", Font.PLAIN, 20);
        
        // Title
        titleLabel = new HStaticText("Resize Mode Test", 0, 10, sceneWidth, 40);
        titleLabel.setFont(titleFont);
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        titleLabel.setVerticalAlignment(HVisible.VALIGN_CENTER);
        scene.add(titleLabel);
        
        // Source image info
        HStaticText sourceInfo = new HStaticText(
            "Source image: " + IMG_WIDTH + "x" + IMG_HEIGHT + " pixels, Container: " + BOX_WIDTH + "x" + BOX_HEIGHT,
            0, 50, sceneWidth, 25);
        sourceInfo.setFont(labelFont);
        sourceInfo.setForeground(Color.LIGHT_GRAY);
        sourceInfo.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        scene.add(sourceInfo);
        
        // Create test image (checkerboard pattern with colored quadrants)
        Image testImage = createTestImage();
        
        // Resize modes to test
        int[] resizeModes = { HVisible.RESIZE_NONE, HVisible.RESIZE_PRESERVE_ASPECT, HVisible.RESIZE_ARBITRARY };
        String[] modeNames = { "RESIZE_NONE", "RESIZE_PRESERVE_ASPECT", "RESIZE_ARBITRARY" };
        
        // Calculate positions
        int totalWidth = 3 * BOX_WIDTH + 2 * BOX_GAP;
        int startX = (sceneWidth - totalWidth) / 2;
        int startY = 100;
        
        for (int i = 0; i < 3; i++) {
            int x = startX + i * (BOX_WIDTH + BOX_GAP);
            
            // Label
            HStaticText label = new HStaticText(modeNames[i], x, startY, BOX_WIDTH, 25);
            label.setFont(labelFont);
            label.setForeground(Color.YELLOW);
            label.setHorizontalAlignment(HVisible.HALIGN_CENTER);
            scene.add(label);
            
            // Icon container with background to show bounds
            HStaticIcon icon = new HStaticIcon(testImage, x, startY + 30, BOX_WIDTH, BOX_HEIGHT);
            icon.setBackground(new Color(40, 40, 40));
            icon.setBackgroundMode(HVisible.BACKGROUND_FILL);
            icon.setHorizontalAlignment(HVisible.HALIGN_CENTER);
            icon.setVerticalAlignment(HVisible.VALIGN_CENTER);
            icon.setResizeMode(resizeModes[i]);
            scene.add(icon);
        }
        
        // Description of expected behavior
        String[] descriptions = {
            "Original size,\ncentered",
            "Scaled to fit,\naspect preserved",
            "Stretched to\nfill container"
        };
        
        for (int i = 0; i < 3; i++) {
            int x = startX + i * (BOX_WIDTH + BOX_GAP);
            int y = startY + 30 + BOX_HEIGHT + 10;
            
            HStaticText desc = new HStaticText(descriptions[i], x, y, BOX_WIDTH, 50);
            desc.setFont(new Font("SansSerif", Font.PLAIN, 14));
            desc.setForeground(Color.LIGHT_GRAY);
            desc.setHorizontalAlignment(HVisible.HALIGN_CENTER);
            desc.setVerticalAlignment(HVisible.VALIGN_TOP);
            scene.add(desc);
        }
        
        // Menu button at bottom
        int buttonWidth = 150;
        int buttonHeight = 40;
        menuButton = new HTextButton("Menu", 
            (sceneWidth - buttonWidth) / 2, 
            sceneHeight - buttonHeight - 20,
            buttonWidth, buttonHeight);
        menuButton.setFont(menuFont);
        menuButton.setForeground(Color.WHITE);
        menuButton.setBackground(new Color(80, 80, 80));
        menuButton.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        menuButton.setVerticalAlignment(HVisible.VALIGN_CENTER);
        menuButton.setActionCommand("menu");
        menuButton.addHActionListener(this);
        scene.add(menuButton);

        System.err.println("ResizeTestXlet: initXlet() complete");
    }
    
    /**
     * Creates a test image with colored quadrants and a border.
     */
    private Image createTestImage() {
        int[] pixels = new int[IMG_WIDTH * IMG_HEIGHT];
        
        int red = 0xFFFF0000;
        int green = 0xFF00FF00;
        int blue = 0xFF0000FF;
        int yellow = 0xFFFFFF00;
        int white = 0xFFFFFFFF;
        
        int halfW = IMG_WIDTH / 2;
        int halfH = IMG_HEIGHT / 2;
        
        for (int y = 0; y < IMG_HEIGHT; y++) {
            for (int x = 0; x < IMG_WIDTH; x++) {
                int idx = y * IMG_WIDTH + x;
                
                // White border (2 pixels)
                if (x < 2 || x >= IMG_WIDTH - 2 || y < 2 || y >= IMG_HEIGHT - 2) {
                    pixels[idx] = white;
                }
                // Colored quadrants
                else if (x < halfW && y < halfH) {
                    pixels[idx] = red;      // Top-left: red
                } else if (x >= halfW && y < halfH) {
                    pixels[idx] = green;    // Top-right: green
                } else if (x < halfW && y >= halfH) {
                    pixels[idx] = blue;     // Bottom-left: blue
                } else {
                    pixels[idx] = yellow;   // Bottom-right: yellow
                }
            }
        }
        
        MemoryImageSource source = new MemoryImageSource(IMG_WIDTH, IMG_HEIGHT, pixels, 0, IMG_WIDTH);
        return scene.createImage(source);
    }

    public void startXlet() throws XletStateChangeException {
        System.err.println("ResizeTestXlet: startXlet()");
        scene.setVisible(true);
        menuButton.requestFocus();
        scene.repaint();
    }

    public void pauseXlet() {
        System.err.println("ResizeTestXlet: pauseXlet()");
    }

    public void destroyXlet(boolean force) throws XletStateChangeException {
        System.err.println("ResizeTestXlet: destroyXlet()");
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
            System.err.println("ResizeTestXlet: Error returning to menu: " + ex.getMessage());
        }
    }
}
