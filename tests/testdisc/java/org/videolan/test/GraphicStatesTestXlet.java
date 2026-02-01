/*
 * This file is part of libbluray
 * Graphic States Test Xlet - tests HGraphicButton interaction states with colored images
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

import org.havi.ui.HGraphicButton;
import org.havi.ui.HScene;
import org.havi.ui.HSceneFactory;
import org.havi.ui.HState;
import org.havi.ui.HStaticIcon;
import org.havi.ui.HStaticText;
import org.havi.ui.HTextButton;
import org.havi.ui.HVisible;
import org.havi.ui.event.HActionListener;

/**
 * Tests HGraphicButton interaction states with colored images.
 * 
 * Displays a 2x2 grid of HGraphicButtons, each with different colored images
 * for different states:
 * - NORMAL: Base color
 * - FOCUSED: Brighter version
 * - ACTIONED: Green tint
 * - DISABLED: Gray/dimmed version
 * 
 * Button 1: Press to toggle Button 3's enabled/disabled state
 * Button 2: Press to toggle Button 4's actioned state
 * Button 3: Demonstrates DISABLED state (starts disabled)
 * Button 4: Demonstrates ACTIONED state
 * 
 * A legend at the top shows which color corresponds to which state.
 */
public class GraphicStatesTestXlet implements Xlet, HActionListener {

    private XletContext context;
    private HScene scene;
    private HStaticText titleLabel;
    private HStaticText descLabel;
    private TestableGraphicButton[] testButtons;
    private HTextButton menuButton;
    
    // Layout constants
    private static final int BUTTON_SIZE = 150;
    private static final int BUTTON_GAP = 30;
    
    // State colors for each button (base colors)
    private static final Color[] BASE_COLORS = {
        new Color(200, 50, 50),   // Button 1: Red
        new Color(50, 50, 200),   // Button 2: Blue
        new Color(200, 200, 50),  // Button 3: Yellow
        new Color(50, 200, 50)    // Button 4: Green
    };
    
    // Track whether borders are enabled (toggled by Button 4)
    private boolean bordersEnabled = false;
    
    /**
     * Subclass of HGraphicButton that exposes setInteractionState for testing.
     */
    private static class TestableGraphicButton extends HGraphicButton {
        public TestableGraphicButton(Image img, int x, int y, int w, int h) {
            super(img, x, y, w, h);
        }
        
        public void setStateForTesting(int state) {
            setInteractionState(state);
        }
    }

    public void initXlet(XletContext context) throws XletStateChangeException {
        System.err.println("GraphicStatesTestXlet: initXlet()");
        this.context = context;

        HSceneFactory factory = HSceneFactory.getInstance();
        scene = factory.getDefaultHScene();
        scene.setLayout(null);

        int sceneWidth = scene.getWidth();
        int sceneHeight = scene.getHeight();
        
        Font titleFont = new Font("SansSerif", Font.BOLD, 24);
        Font legendFont = new Font("SansSerif", Font.PLAIN, 14);
        Font descFont = new Font("SansSerif", Font.PLAIN, 14);
        Font menuFont = new Font("SansSerif", Font.PLAIN, 18);
        
        // Title
        titleLabel = new HStaticText("HGraphicButton States Test", 0, 5, sceneWidth, 30);
        titleLabel.setFont(titleFont);
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        titleLabel.setVerticalAlignment(HVisible.VALIGN_CENTER);
        scene.add(titleLabel);
        
        // Visual legend showing all 8 state colors with swatches (2 rows of 4)
        // Using red as reference color to show state variations
        Color refColor = new Color(200, 50, 50);  // Red reference
        int swatchSize = 18;
        int legendRowHeight = 22;
        int legendY1 = 36;
        int legendY2 = legendY1 + legendRowHeight;
        int legendItemWidth = 130;
        int legendStartX = (sceneWidth - 4 * legendItemWidth) / 2;
        
        // Calculate state colors (same logic as setupButtonStateImages)
        Color normalColor = refColor;
        Color focusedColor = brighten(refColor, 0.4f);
        Color actionedColor = blend(refColor, Color.GREEN, 0.5f);
        Color actionedFocusedColor = brighten(actionedColor, 0.3f);
        Color disabledColor = grayscale(refColor);
        Color disabledFocusedColor = brighten(disabledColor, 0.2f);
        Color disabledActionedColor = blend(disabledColor, Color.GREEN, 0.2f);
        Color disabledActionedFocusedColor = brighten(disabledActionedColor, 0.2f);
        
        // Row 1: NORMAL, FOCUSED, ACTIONED, ACT+FOCUS
        String[] row1Labels = {"NORMAL", "FOCUSED", "ACTIONED", "ACT+FOC"};
        Color[] row1Colors = {normalColor, focusedColor, actionedColor, actionedFocusedColor};
        
        for (int i = 0; i < 4; i++) {
            int x = legendStartX + i * legendItemWidth;
            Image swatch = createColorImage(row1Colors[i], swatchSize, swatchSize);
            HStaticIcon icon = new HStaticIcon(swatch, x, legendY1, swatchSize, swatchSize);
            scene.add(icon);
            HStaticText label = new HStaticText(row1Labels[i], x + swatchSize + 3, legendY1, 90, swatchSize);
            label.setFont(legendFont);
            label.setForeground(Color.WHITE);
            label.setVerticalAlignment(HVisible.VALIGN_CENTER);
            scene.add(label);
        }
        
        // Row 2: DISABLED, DIS+FOCUS, DIS+ACT, DIS+ACT+FOC
        String[] row2Labels = {"DISABLED", "DIS+FOC", "DIS+ACT", "DIS+A+F"};
        Color[] row2Colors = {disabledColor, disabledFocusedColor, disabledActionedColor, disabledActionedFocusedColor};
        
        for (int i = 0; i < 4; i++) {
            int x = legendStartX + i * legendItemWidth;
            Image swatch = createColorImage(row2Colors[i], swatchSize, swatchSize);
            HStaticIcon icon = new HStaticIcon(swatch, x, legendY2, swatchSize, swatchSize);
            scene.add(icon);
            HStaticText label = new HStaticText(row2Labels[i], x + swatchSize + 3, legendY2, 90, swatchSize);
            label.setFont(legendFont);
            label.setForeground(Color.WHITE);
            label.setVerticalAlignment(HVisible.VALIGN_CENTER);
            scene.add(label);
        }
        
        // Description
        descLabel = new HStaticText(
            "Btn1: toggle B3 disabled | Btn2: toggle B4 actioned | Btn4: toggle borders",
            20, 82, sceneWidth - 40, 22);
        descLabel.setFont(descFont);
        descLabel.setForeground(Color.LIGHT_GRAY);
        descLabel.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        descLabel.setVerticalAlignment(HVisible.VALIGN_CENTER);
        scene.add(descLabel);
        
        // Create 2x2 grid of buttons
        testButtons = new TestableGraphicButton[4];
        
        int gridWidth = 2 * BUTTON_SIZE + BUTTON_GAP;
        int gridHeight = 2 * BUTTON_SIZE + BUTTON_GAP;
        int startX = (sceneWidth - gridWidth) / 2;
        int startY = 110;
        
        for (int i = 0; i < 4; i++) {
            int row = i / 2;
            int col = i % 2;
            int x = startX + col * (BUTTON_SIZE + BUTTON_GAP);
            int y = startY + row * (BUTTON_SIZE + BUTTON_GAP);
            
            // Create button with normal state image
            Image normalImg = createColorImage(BASE_COLORS[i], BUTTON_SIZE, BUTTON_SIZE);
            testButtons[i] = new TestableGraphicButton(normalImg, x, y, BUTTON_SIZE, BUTTON_SIZE);
            testButtons[i].setActionCommand("button_" + (i + 1));
            testButtons[i].addHActionListener(this);
            testButtons[i].setForeground(Color.WHITE);  // Border color when enabled
            
            // Set state-specific images
            setupButtonStateImages(i);
            
            scene.add(testButtons[i]);
        }
        
        // Add button number labels below each button
        String[] labels = {"1: Toggle B3", "2: Toggle B4", "3: Disabled", "4: Borders"};
        Font labelFont = new Font("SansSerif", Font.BOLD, 16);
        for (int i = 0; i < 4; i++) {
            int row = i / 2;
            int col = i % 2;
            int x = startX + col * (BUTTON_SIZE + BUTTON_GAP);
            int y = startY + row * (BUTTON_SIZE + BUTTON_GAP) + BUTTON_SIZE + 2;
            
            HStaticText label = new HStaticText(labels[i], x, y, BUTTON_SIZE, 20);
            label.setFont(labelFont);
            label.setForeground(Color.WHITE);
            label.setHorizontalAlignment(HVisible.HALIGN_CENTER);
            label.setVerticalAlignment(HVisible.VALIGN_CENTER);
            scene.add(label);
        }
        
        // Button 3 starts disabled
        testButtons[2].setEnabled(false);
        
        // Set up 2x2 navigation
        // Row 0: Button 0, Button 1
        // Row 1: Button 2, Button 3
        testButtons[0].setFocusTraversal(null, testButtons[2], null, testButtons[1]);
        testButtons[1].setFocusTraversal(null, testButtons[3], testButtons[0], null);
        testButtons[2].setFocusTraversal(testButtons[0], menuButton, null, testButtons[3]);
        testButtons[3].setFocusTraversal(testButtons[1], menuButton, testButtons[2], null);
        
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
        
        // Update navigation for menu button
        testButtons[2].setFocusTraversal(testButtons[0], menuButton, null, testButtons[3]);
        testButtons[3].setFocusTraversal(testButtons[1], menuButton, testButtons[2], null);
        menuButton.setFocusTraversal(testButtons[2], testButtons[0], null, null);
        
        scene.add(menuButton);

        System.err.println("GraphicStatesTestXlet: initXlet() complete");
    }
    
    /**
     * Sets up state-specific images for a button.
     * Each state gets a different color variation.
     */
    private void setupButtonStateImages(int buttonIndex) {
        Color base = BASE_COLORS[buttonIndex];
        
        // NORMAL: base color
        Image normalImg = createColorImage(base, BUTTON_SIZE, BUTTON_SIZE);
        testButtons[buttonIndex].setGraphicContent(normalImg, HState.NORMAL_STATE);
        
        // FOCUSED: brighter version
        Color focused = brighten(base, 0.4f);
        Image focusedImg = createColorImage(focused, BUTTON_SIZE, BUTTON_SIZE);
        testButtons[buttonIndex].setGraphicContent(focusedImg, HState.FOCUSED_STATE);
        
        // ACTIONED: green tint overlay
        Color actioned = blend(base, Color.GREEN, 0.5f);
        Image actionedImg = createColorImage(actioned, BUTTON_SIZE, BUTTON_SIZE);
        testButtons[buttonIndex].setGraphicContent(actionedImg, HState.ACTIONED_STATE);
        
        // ACTIONED_FOCUSED: bright green tint
        Color actionedFocused = brighten(actioned, 0.3f);
        Image actionedFocusedImg = createColorImage(actionedFocused, BUTTON_SIZE, BUTTON_SIZE);
        testButtons[buttonIndex].setGraphicContent(actionedFocusedImg, HState.ACTIONED_FOCUSED_STATE);
        
        // DISABLED: grayscale version
        Color disabled = grayscale(base);
        Image disabledImg = createColorImage(disabled, BUTTON_SIZE, BUTTON_SIZE);
        testButtons[buttonIndex].setGraphicContent(disabledImg, HState.DISABLED_STATE);
        
        // DISABLED_FOCUSED: slightly brighter grayscale
        Color disabledFocused = brighten(disabled, 0.2f);
        Image disabledFocusedImg = createColorImage(disabledFocused, BUTTON_SIZE, BUTTON_SIZE);
        testButtons[buttonIndex].setGraphicContent(disabledFocusedImg, HState.DISABLED_FOCUSED_STATE);
        
        // DISABLED_ACTIONED: grayscale with slight green
        Color disabledActioned = blend(disabled, Color.GREEN, 0.2f);
        Image disabledActionedImg = createColorImage(disabledActioned, BUTTON_SIZE, BUTTON_SIZE);
        testButtons[buttonIndex].setGraphicContent(disabledActionedImg, HState.DISABLED_ACTIONED_STATE);
        
        // DISABLED_ACTIONED_FOCUSED: bright grayscale green
        Color disabledActionedFocused = brighten(disabledActioned, 0.2f);
        Image disabledActionedFocusedImg = createColorImage(disabledActionedFocused, BUTTON_SIZE, BUTTON_SIZE);
        testButtons[buttonIndex].setGraphicContent(disabledActionedFocusedImg, HState.DISABLED_ACTIONED_FOCUSED_STATE);
    }
    
    /**
     * Creates a solid color image.
     */
    private Image createColorImage(Color color, int width, int height) {
        int[] pixels = new int[width * height];
        int rgb = color.getRGB();
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = rgb;
        }
        return scene.getToolkit().createImage(
            new MemoryImageSource(width, height, pixels, 0, width));
    }
    
    /**
     * Brightens a color by a factor (0.0 = no change, 1.0 = white).
     */
    private Color brighten(Color c, float factor) {
        int r = Math.min(255, (int)(c.getRed() + (255 - c.getRed()) * factor));
        int g = Math.min(255, (int)(c.getGreen() + (255 - c.getGreen()) * factor));
        int b = Math.min(255, (int)(c.getBlue() + (255 - c.getBlue()) * factor));
        return new Color(r, g, b);
    }
    
    /**
     * Converts a color to grayscale.
     */
    private Color grayscale(Color c) {
        int gray = (int)(c.getRed() * 0.3 + c.getGreen() * 0.59 + c.getBlue() * 0.11);
        return new Color(gray, gray, gray);
    }
    
    /**
     * Blends two colors together.
     */
    private Color blend(Color c1, Color c2, float factor) {
        int r = (int)(c1.getRed() * (1 - factor) + c2.getRed() * factor);
        int g = (int)(c1.getGreen() * (1 - factor) + c2.getGreen() * factor);
        int b = (int)(c1.getBlue() * (1 - factor) + c2.getBlue() * factor);
        return new Color(r, g, b);
    }

    public void startXlet() throws XletStateChangeException {
        System.err.println("GraphicStatesTestXlet: startXlet()");
        scene.setVisible(true);
        testButtons[0].requestFocus();
        scene.repaint();
    }

    public void pauseXlet() {
        System.err.println("GraphicStatesTestXlet: pauseXlet()");
    }

    public void destroyXlet(boolean force) throws XletStateChangeException {
        System.err.println("GraphicStatesTestXlet: destroyXlet()");
        if (scene != null) {
            scene.setVisible(false);
            scene.removeAll();
            scene.dispose();
            scene = null;
        }
    }

    public void actionPerformed(ActionEvent e) {
        String command = e.getActionCommand();
        System.err.println("GraphicStatesTestXlet: actionPerformed(" + command + ")");
        
        if ("menu".equals(command)) {
            returnToMenu();
        } else if ("button_1".equals(command)) {
            // Toggle Button 3's enabled/disabled state
            boolean newEnabled = !testButtons[2].isEnabled();
            testButtons[2].setEnabled(newEnabled);
            testButtons[2].repaint();
            System.err.println("GraphicStatesTestXlet: Button 3 enabled=" + newEnabled);
        } else if ("button_2".equals(command)) {
            // Toggle Button 4's actioned state bit
            int state = testButtons[3].getInteractionState();
            if ((state & HState.ACTIONED_STATE_BIT) != 0) {
                // Clear actioned bit
                testButtons[3].setStateForTesting(state & ~HState.ACTIONED_STATE_BIT);
                System.err.println("GraphicStatesTestXlet: Button 4 actioned=false");
            } else {
                // Set actioned bit
                testButtons[3].setStateForTesting(state | HState.ACTIONED_STATE_BIT);
                System.err.println("GraphicStatesTestXlet: Button 4 actioned=true");
            }
            testButtons[3].repaint();
        } else if ("button_4".equals(command)) {
            // Toggle borders on all buttons
            bordersEnabled = !bordersEnabled;
            for (int i = 0; i < testButtons.length; i++) {
                testButtons[i].setBordersEnabled(bordersEnabled);
                testButtons[i].repaint();
            }
            System.err.println("GraphicStatesTestXlet: Borders enabled=" + bordersEnabled);
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
            System.err.println("GraphicStatesTestXlet: Error returning to menu: " + ex.getMessage());
        }
    }
}
