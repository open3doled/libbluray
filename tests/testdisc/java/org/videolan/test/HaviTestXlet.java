/*
 * This file is part of libbluray
 * HAVI Test Xlet for testing HScene, HStaticIcon, and HGraphicButton
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 */

package org.videolan.test;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.image.MemoryImageSource;

import javax.tv.xlet.Xlet;
import javax.tv.xlet.XletContext;
import javax.tv.xlet.XletStateChangeException;

import org.havi.ui.HGraphicButton;
import org.havi.ui.HScene;
import org.havi.ui.HSceneFactory;
import org.havi.ui.HStaticIcon;
import org.havi.ui.event.HActionListener;

/**
 * A simple HAVI test Xlet with:
 * - 4 HStaticIcons (top/middle area)
 * - 4 HGraphicButtons (bottom area, 2x2 grid, navigable up/down/left/right)
 * 
 * Pressing ENTER on a button toggles visibility of its associated icon.
 * Each icon matches the color of its controlling button.
 * 
 * Layout:
 *   [Icon1-Green]  [Icon2-Yellow]    <- Top row icons
 *   [Icon3-Cyan]   [Icon4-Magenta]   <- Middle row icons
 *   
 *   [Btn1-Green]   [Btn2-Yellow]     <- Top row buttons (left/right nav)
 *   [Btn3-Cyan]    [Btn4-Magenta]    <- Bottom row buttons (left/right nav)
 *                                       (up/down nav between rows)
 */
public class HaviTestXlet implements Xlet, HActionListener {

    private XletContext context;
    private HScene scene;
    private HStaticIcon icon1;
    private HStaticIcon icon2;
    private HStaticIcon icon3;
    private HStaticIcon icon4;
    private HGraphicButton button1;
    private HGraphicButton button2;
    private HGraphicButton button3;
    private HGraphicButton button4;

    // Constants for layout
    private static final int ICON_SIZE = 80;
    private static final int BUTTON_WIDTH = 140;
    private static final int BUTTON_HEIGHT = 50;
    private static final int MARGIN = 40;
    private static final int BUTTON_GAP = 20;

    public void initXlet(XletContext context) throws XletStateChangeException {
        System.err.println("HaviTestXlet: initXlet()");
        this.context = context;

        // Get the default HScene
        HSceneFactory factory = HSceneFactory.getInstance();
        scene = factory.getDefaultHScene();
        scene.setLayout(null); // Use absolute positioning

        int sceneWidth = scene.getWidth();
        int sceneHeight = scene.getHeight();
        
        // Calculate positions for 2x2 icon grid (top half of screen)
        int iconRowY1 = MARGIN;
        int iconRowY2 = MARGIN + ICON_SIZE + MARGIN;
        int iconColX1 = MARGIN;
        int iconColX2 = sceneWidth - ICON_SIZE - MARGIN;

        // Create icons (2x2 grid in top half)
        // Each icon matches the color of its controlling button
        
        // Icon 1 - Green square (top-left) - controlled by green button
        Image greenIconImage = createColoredImage(ICON_SIZE, ICON_SIZE, Color.GREEN);
        icon1 = new HStaticIcon(greenIconImage, iconColX1, iconRowY1, ICON_SIZE, ICON_SIZE);
        icon1.setVisible(true);

        // Icon 2 - Yellow square (top-right) - controlled by yellow button
        Image yellowIconImage = createColoredImage(ICON_SIZE, ICON_SIZE, Color.YELLOW);
        icon2 = new HStaticIcon(yellowIconImage, iconColX2, iconRowY1, ICON_SIZE, ICON_SIZE);
        icon2.setVisible(true);

        // Icon 3 - Cyan square (middle-left) - controlled by cyan button
        Image cyanIconImage = createColoredImage(ICON_SIZE, ICON_SIZE, Color.CYAN);
        icon3 = new HStaticIcon(cyanIconImage, iconColX1, iconRowY2, ICON_SIZE, ICON_SIZE);
        icon3.setVisible(true);

        // Icon 4 - Magenta square (middle-right) - controlled by magenta button
        Image magentaIconImage = createColoredImage(ICON_SIZE, ICON_SIZE, Color.MAGENTA);
        icon4 = new HStaticIcon(magentaIconImage, iconColX2, iconRowY2, ICON_SIZE, ICON_SIZE);
        icon4.setVisible(true);

        // Calculate positions for 2x2 button grid (bottom half of screen)
        int buttonRowY1 = sceneHeight - (BUTTON_HEIGHT * 2) - BUTTON_GAP - MARGIN;
        int buttonRowY2 = sceneHeight - BUTTON_HEIGHT - MARGIN;
        int buttonColX1 = MARGIN;
        int buttonColX2 = sceneWidth - BUTTON_WIDTH - MARGIN;

        // Create buttons (2x2 grid in bottom half)
        // Button 1 - Green button (top-left, controls Icon 1)
        Image greenNormal = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.GREEN.darker(), "Icon 1");
        Image greenFocused = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.GREEN, "Icon 1");
        Image greenActioned = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.GREEN.brighter(), "Icon 1");
        button1 = new HGraphicButton(greenNormal, greenFocused, greenActioned,
            buttonColX1, buttonRowY1, BUTTON_WIDTH, BUTTON_HEIGHT);
        button1.setActionCommand("toggle_icon1");
        button1.addHActionListener(this);

        // Button 2 - Yellow button (top-right, controls Icon 2)
        Image yellowNormal = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.YELLOW.darker(), "Icon 2");
        Image yellowFocused = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.YELLOW, "Icon 2");
        Image yellowActioned = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.YELLOW.brighter(), "Icon 2");
        button2 = new HGraphicButton(yellowNormal, yellowFocused, yellowActioned,
            buttonColX2, buttonRowY1, BUTTON_WIDTH, BUTTON_HEIGHT);
        button2.setActionCommand("toggle_icon2");
        button2.addHActionListener(this);

        // Button 3 - Cyan button (bottom-left, controls Icon 3)
        Image cyanNormal = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.CYAN.darker(), "Icon 3");
        Image cyanFocused = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.CYAN, "Icon 3");
        Image cyanActioned = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.CYAN.brighter(), "Icon 3");
        button3 = new HGraphicButton(cyanNormal, cyanFocused, cyanActioned,
            buttonColX1, buttonRowY2, BUTTON_WIDTH, BUTTON_HEIGHT);
        button3.setActionCommand("toggle_icon3");
        button3.addHActionListener(this);

        // Button 4 - Magenta button (bottom-right, controls Icon 4)
        Image magentaNormal = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.MAGENTA.darker(), "Icon 4");
        Image magentaFocused = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.MAGENTA, "Icon 4");
        Image magentaActioned = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.MAGENTA.brighter(), "Icon 4");
        button4 = new HGraphicButton(magentaNormal, magentaFocused, magentaActioned,
            buttonColX2, buttonRowY2, BUTTON_WIDTH, BUTTON_HEIGHT);
        button4.setActionCommand("toggle_icon4");
        button4.addHActionListener(this);

        // Set up navigation between buttons (2x2 grid)
        // setFocusTraversal(up, down, left, right)
        button1.setFocusTraversal(button3, button3, button2, button2);  // top-left
        button2.setFocusTraversal(button4, button4, button1, button1);  // top-right
        button3.setFocusTraversal(button1, button1, button4, button4);  // bottom-left
        button4.setFocusTraversal(button2, button2, button3, button3);  // bottom-right

        // Add components to scene
        // Add in z-order: icons first (back), buttons on top (front)
        scene.add(icon1);
        scene.add(icon2);
        scene.add(icon3);
        scene.add(icon4);
        scene.add(button1);
        scene.add(button2);
        scene.add(button3);
        scene.add(button4);

        System.err.println("HaviTestXlet: initXlet() complete");
        System.err.println("  Scene size: " + sceneWidth + "x" + sceneHeight);
        System.err.println("  Icon1 bounds: " + icon1.getBounds());
        System.err.println("  Icon2 bounds: " + icon2.getBounds());
        System.err.println("  Icon3 bounds: " + icon3.getBounds());
        System.err.println("  Icon4 bounds: " + icon4.getBounds());
        System.err.println("  Button1 bounds: " + button1.getBounds());
        System.err.println("  Button2 bounds: " + button2.getBounds());
        System.err.println("  Button3 bounds: " + button3.getBounds());
        System.err.println("  Button4 bounds: " + button4.getBounds());
    }

    public void startXlet() throws XletStateChangeException {
        System.err.println("HaviTestXlet: startXlet()");
        
        // Make scene visible
        scene.setVisible(true);

        // Request focus on button1 to start
        // HVisible.requestFocus() automatically sets FOCUSED_STATE_BIT
        button1.requestFocus();
        
        scene.repaint();
        
        System.err.println("HaviTestXlet: startXlet() complete");
        System.err.println("  Use UP/DOWN/LEFT/RIGHT arrows to navigate between buttons");
        System.err.println("  Press ENTER to toggle visibility of the associated icon");
    }

    public void pauseXlet() {
        System.err.println("HaviTestXlet: pauseXlet()");
    }

    public void destroyXlet(boolean force) throws XletStateChangeException {
        System.err.println("HaviTestXlet: destroyXlet()");
        if (scene != null) {
            scene.setVisible(false);
            scene.removeAll();
            scene.dispose();
            scene = null;
        }
    }

    /**
     * Handle button activation - toggle visibility of associated icon
     */
    public void actionPerformed(ActionEvent e) {
        String command = e.getActionCommand();
        System.err.println("HaviTestXlet: actionPerformed(" + command + ")");

        if ("toggle_icon1".equals(command)) {
            boolean newVisibility = !icon1.isVisible();
            icon1.setVisible(newVisibility);
            System.err.println("HaviTestXlet: Icon 1 (Green) visibility now: " + newVisibility);
        } else if ("toggle_icon2".equals(command)) {
            boolean newVisibility = !icon2.isVisible();
            icon2.setVisible(newVisibility);
            System.err.println("HaviTestXlet: Icon 2 (Yellow) visibility now: " + newVisibility);
        } else if ("toggle_icon3".equals(command)) {
            boolean newVisibility = !icon3.isVisible();
            icon3.setVisible(newVisibility);
            System.err.println("HaviTestXlet: Icon 3 (Cyan) visibility now: " + newVisibility);
        } else if ("toggle_icon4".equals(command)) {
            boolean newVisibility = !icon4.isVisible();
            icon4.setVisible(newVisibility);
            System.err.println("HaviTestXlet: Icon 4 (Magenta) visibility now: " + newVisibility);
        }

        scene.repaint();
    }

    /**
     * Create a simple colored square image using MemoryImageSource (BD-J compatible)
     */
    private Image createColoredImage(int width, int height, Color color) {
        int[] pixels = new int[width * height];
        int colorRgb = color.getRGB();
        int borderRgb = Color.WHITE.getRGB();
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Draw border (1 pixel)
                if (x == 0 || x == width - 1 || y == 0 || y == height - 1) {
                    pixels[y * width + x] = borderRgb;
                } else {
                    pixels[y * width + x] = colorRgb;
                }
            }
        }
        
        MemoryImageSource source = new MemoryImageSource(width, height, pixels, 0, width);
        return Toolkit.getDefaultToolkit().createImage(source);
    }

    /**
     * Create a button image with color (BD-J compatible)
     * Note: Text rendering on raw pixel arrays is complex, so we just use colored rectangles
     * with a distinct border to indicate button state
     */
    private Image createButtonImage(int width, int height, Color color, String label) {
        int[] pixels = new int[width * height];
        int colorRgb = color.getRGB();
        int borderRgb = Color.WHITE.getRGB();
        int darkBorderRgb = color.darker().getRGB();
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Create a simple rounded-corner effect with borders
                boolean isCorner = (x < 5 && y < 5) || (x < 5 && y >= height - 5) ||
                                   (x >= width - 5 && y < 5) || (x >= width - 5 && y >= height - 5);
                
                // Skip corner pixels for rounded effect
                if (isCorner) {
                    int dx = (x < 5) ? x : width - 1 - x;
                    int dy = (y < 5) ? y : height - 1 - y;
                    if (dx + dy < 3) {
                        pixels[y * width + x] = 0; // Transparent
                        continue;
                    }
                }
                
                // Outer border (2 pixels)
                if (x < 2 || x >= width - 2 || y < 2 || y >= height - 2) {
                    pixels[y * width + x] = borderRgb;
                }
                // Inner darker border (1 pixel) for 3D effect
                else if (x < 4 || x >= width - 4 || y < 4 || y >= height - 4) {
                    pixels[y * width + x] = darkBorderRgb;
                }
                // Fill color
                else {
                    pixels[y * width + x] = colorRgb;
                }
            }
        }
        
        MemoryImageSource source = new MemoryImageSource(width, height, pixels, 0, width);
        return Toolkit.getDefaultToolkit().createImage(source);
    }
}
