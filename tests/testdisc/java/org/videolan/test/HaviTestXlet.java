/*
 * This file is part of libbluray
 * HAVI Test Xlet for testing HScene, HStaticIcon, HGraphicButton, HStaticText, and HTextButton
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
import org.havi.ui.HStaticText;
import org.havi.ui.HTextButton;
import org.havi.ui.event.HActionListener;

/**
 * A simple HAVI test Xlet with:
 * - 4 HStaticIcons (top-left area) with 4 HGraphicButtons controlling them
 * - 4 HStaticTexts (top-right area) with 4 HTextButtons controlling them
 * 
 * Pressing ENTER on a button toggles visibility of its associated icon/text.
 * 
 * Layout (left side - graphic test):
 *   [Icon1-Green]  [Icon2-Yellow]    <- Top row icons
 *   [Icon3-Cyan]   [Icon4-Magenta]   <- Second row icons
 *   [GBtn1-Green]  [GBtn2-Yellow]    <- Graphic buttons row 1
 *   [GBtn3-Cyan]   [GBtn4-Magenta]   <- Graphic buttons row 2
 *
 * Layout (right side - text test):
 *   [Text1]        [Text2]           <- Top row static texts
 *   [Text3]        [Text4]           <- Second row static texts
 *   [Button 1]     [Button 2]        <- Text buttons row 1
 *   [Button 3]     [Button 4]        <- Text buttons row 2
 */
public class HaviTestXlet implements Xlet, HActionListener {

    private XletContext context;
    private HScene scene;
    
    // Graphic button test components (left side)
    private HStaticIcon icon1;
    private HStaticIcon icon2;
    private HStaticIcon icon3;
    private HStaticIcon icon4;
    private HGraphicButton button1;
    private HGraphicButton button2;
    private HGraphicButton button3;
    private HGraphicButton button4;
    
    // Text button test components (right side)
    private HStaticText text1;
    private HStaticText text2;
    private HStaticText text3;
    private HStaticText text4;
    private HTextButton textButton1;
    private HTextButton textButton2;
    private HTextButton textButton3;
    private HTextButton textButton4;

    // Constants for layout
    private static final int ICON_SIZE = 80;
    private static final int BUTTON_WIDTH = 140;
    private static final int BUTTON_HEIGHT = 50;
    private static final int TEXT_WIDTH = 120;
    private static final int TEXT_HEIGHT = 40;
    private static final int MARGIN = 30;
    private static final int GAP = 15;

    public void initXlet(XletContext context) throws XletStateChangeException {
        System.err.println("HaviTestXlet: initXlet()");
        this.context = context;

        // Get the default HScene
        HSceneFactory factory = HSceneFactory.getInstance();
        scene = factory.getDefaultHScene();
        scene.setLayout(null); // Use absolute positioning

        int sceneWidth = scene.getWidth();
        int sceneHeight = scene.getHeight();
        int halfWidth = sceneWidth / 2;
        
        // ========== LEFT SIDE: HGraphicButton + HStaticIcon test ==========
        
        // Calculate positions for 2x2 icon grid (left side, top area)
        int iconRowY1 = MARGIN;
        int iconRowY2 = MARGIN + ICON_SIZE + GAP;
        int iconColX1 = MARGIN;
        int iconColX2 = MARGIN + ICON_SIZE + GAP;

        // Create icons (2x2 grid)
        // Icon 1 - Green square (top-left)
        Image greenIconImage = createColoredImage(ICON_SIZE, ICON_SIZE, Color.GREEN);
        icon1 = new HStaticIcon(greenIconImage, iconColX1, iconRowY1, ICON_SIZE, ICON_SIZE);
        icon1.setVisible(true);

        // Icon 2 - Yellow square (top-right)
        Image yellowIconImage = createColoredImage(ICON_SIZE, ICON_SIZE, Color.YELLOW);
        icon2 = new HStaticIcon(yellowIconImage, iconColX2, iconRowY1, ICON_SIZE, ICON_SIZE);
        icon2.setVisible(true);

        // Icon 3 - Cyan square (bottom-left)
        Image cyanIconImage = createColoredImage(ICON_SIZE, ICON_SIZE, Color.CYAN);
        icon3 = new HStaticIcon(cyanIconImage, iconColX1, iconRowY2, ICON_SIZE, ICON_SIZE);
        icon3.setVisible(true);

        // Icon 4 - Magenta square (bottom-right)
        Image magentaIconImage = createColoredImage(ICON_SIZE, ICON_SIZE, Color.MAGENTA);
        icon4 = new HStaticIcon(magentaIconImage, iconColX2, iconRowY2, ICON_SIZE, ICON_SIZE);
        icon4.setVisible(true);

        // Calculate positions for 2x2 graphic button grid (left side, bottom area)
        int gButtonRowY1 = iconRowY2 + ICON_SIZE + MARGIN;
        int gButtonRowY2 = gButtonRowY1 + BUTTON_HEIGHT + GAP;
        int gButtonColX1 = MARGIN;
        int gButtonColX2 = MARGIN + BUTTON_WIDTH + GAP;

        // Create graphic buttons (2x2 grid)
        // Button 1 - Green button (controls Icon 1)
        Image greenNormal = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.GREEN.darker(), "Icon 1");
        Image greenFocused = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.GREEN, "Icon 1");
        Image greenActioned = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.GREEN.brighter(), "Icon 1");
        button1 = new HGraphicButton(greenNormal, greenFocused, greenActioned,
            gButtonColX1, gButtonRowY1, BUTTON_WIDTH, BUTTON_HEIGHT);
        button1.setActionCommand("toggle_icon1");
        button1.addHActionListener(this);

        // Button 2 - Yellow button (controls Icon 2)
        Image yellowNormal = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.YELLOW.darker(), "Icon 2");
        Image yellowFocused = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.YELLOW, "Icon 2");
        Image yellowActioned = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.YELLOW.brighter(), "Icon 2");
        button2 = new HGraphicButton(yellowNormal, yellowFocused, yellowActioned,
            gButtonColX2, gButtonRowY1, BUTTON_WIDTH, BUTTON_HEIGHT);
        button2.setActionCommand("toggle_icon2");
        button2.addHActionListener(this);

        // Button 3 - Cyan button (controls Icon 3)
        Image cyanNormal = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.CYAN.darker(), "Icon 3");
        Image cyanFocused = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.CYAN, "Icon 3");
        Image cyanActioned = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.CYAN.brighter(), "Icon 3");
        button3 = new HGraphicButton(cyanNormal, cyanFocused, cyanActioned,
            gButtonColX1, gButtonRowY2, BUTTON_WIDTH, BUTTON_HEIGHT);
        button3.setActionCommand("toggle_icon3");
        button3.addHActionListener(this);

        // Button 4 - Magenta button (controls Icon 4)
        Image magentaNormal = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.MAGENTA.darker(), "Icon 4");
        Image magentaFocused = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.MAGENTA, "Icon 4");
        Image magentaActioned = createButtonImage(BUTTON_WIDTH, BUTTON_HEIGHT, Color.MAGENTA.brighter(), "Icon 4");
        button4 = new HGraphicButton(magentaNormal, magentaFocused, magentaActioned,
            gButtonColX2, gButtonRowY2, BUTTON_WIDTH, BUTTON_HEIGHT);
        button4.setActionCommand("toggle_icon4");
        button4.addHActionListener(this);

        // ========== RIGHT SIDE: HTextButton + HStaticText test ==========
        
        // Calculate positions for 2x2 static text grid (right side, top area)
        int textRowY1 = MARGIN;
        int textRowY2 = MARGIN + TEXT_HEIGHT + GAP;
        int textColX1 = halfWidth + MARGIN;
        int textColX2 = halfWidth + MARGIN + TEXT_WIDTH + GAP;

        // Create static texts (2x2 grid) - these will be toggled by text buttons
        Font textFont = new Font("SansSerif", Font.BOLD, 24);
        
        text1 = new HStaticText("Text 1", textColX1, textRowY1, TEXT_WIDTH, TEXT_HEIGHT);
        text1.setForeground(Color.WHITE);
        text1.setBackground(new Color(0, 100, 0)); // Dark green background
        text1.setFont(textFont);
        text1.setVisible(true);

        text2 = new HStaticText("Text 2", textColX2, textRowY1, TEXT_WIDTH, TEXT_HEIGHT);
        text2.setForeground(Color.WHITE);
        text2.setBackground(new Color(150, 150, 0)); // Dark yellow background
        text2.setFont(textFont);
        text2.setVisible(true);

        text3 = new HStaticText("Text 3", textColX1, textRowY2, TEXT_WIDTH, TEXT_HEIGHT);
        text3.setForeground(Color.WHITE);
        text3.setBackground(new Color(0, 100, 100)); // Dark cyan background
        text3.setFont(textFont);
        text3.setVisible(true);

        text4 = new HStaticText("Text 4", textColX2, textRowY2, TEXT_WIDTH, TEXT_HEIGHT);
        text4.setForeground(Color.WHITE);
        text4.setBackground(new Color(100, 0, 100)); // Dark magenta background
        text4.setFont(textFont);
        text4.setVisible(true);

        // Calculate positions for 2x2 text button grid (right side, below static texts)
        int tButtonRowY1 = textRowY2 + TEXT_HEIGHT + MARGIN;
        int tButtonRowY2 = tButtonRowY1 + BUTTON_HEIGHT + GAP;
        int tButtonColX1 = halfWidth + MARGIN;
        int tButtonColX2 = halfWidth + MARGIN + BUTTON_WIDTH + GAP;

        // Create text buttons (2x2 grid) - using text labels instead of images
        textButton1 = new HTextButton("Button 1", tButtonColX1, tButtonRowY1, BUTTON_WIDTH, BUTTON_HEIGHT);
        textButton1.setFont(textFont);
        textButton1.setForeground(Color.WHITE);
        textButton1.setBackground(Color.DARK_GRAY);
        textButton1.setActionCommand("toggle_text1");
        textButton1.addHActionListener(this);

        textButton2 = new HTextButton("Button 2", tButtonColX2, tButtonRowY1, BUTTON_WIDTH, BUTTON_HEIGHT);
        textButton2.setFont(textFont);
        textButton2.setForeground(Color.WHITE);
        textButton2.setBackground(Color.DARK_GRAY);
        textButton2.setActionCommand("toggle_text2");
        textButton2.addHActionListener(this);

        textButton3 = new HTextButton("Button 3", tButtonColX1, tButtonRowY2, BUTTON_WIDTH, BUTTON_HEIGHT);
        textButton3.setFont(textFont);
        textButton3.setForeground(Color.WHITE);
        textButton3.setBackground(Color.DARK_GRAY);
        textButton3.setActionCommand("toggle_text3");
        textButton3.addHActionListener(this);

        textButton4 = new HTextButton("Button 4", tButtonColX2, tButtonRowY2, BUTTON_WIDTH, BUTTON_HEIGHT);
        textButton4.setFont(textFont);
        textButton4.setForeground(Color.WHITE);
        textButton4.setBackground(Color.DARK_GRAY);
        textButton4.setActionCommand("toggle_text4");
        textButton4.addHActionListener(this);

        // ========== Set up navigation ==========
        // setFocusTraversal(up, down, left, right)
        //
        // Layout (each row is a circular navigation path):
        //   [green]  [yellow]  [Button 1]  [Button 2]   <- Row 1
        //   [cyan]   [magenta] [Button 3]  [Button 4]   <- Row 2
        //
        // Row 1: green <-> yellow <-> Button 1 <-> Button 2 <-> green (wraps)
        // Row 2: cyan <-> magenta <-> Button 3 <-> Button 4 <-> cyan (wraps)
        
        // Row 1 navigation
        button1.setFocusTraversal(button3, button3, textButton2, button2);      // green: left->Button 2, right->yellow
        button2.setFocusTraversal(button4, button4, button1, textButton1);      // yellow: left->green, right->Button 1
        textButton1.setFocusTraversal(textButton3, textButton3, button2, textButton2);  // Button 1: left->yellow, right->Button 2
        textButton2.setFocusTraversal(textButton4, textButton4, textButton1, button1);  // Button 2: left->Button 1, right->green (wrap)

        // Row 2 navigation
        button3.setFocusTraversal(button1, button1, textButton4, button4);      // cyan: left->Button 4, right->magenta
        button4.setFocusTraversal(button2, button2, button3, textButton3);      // magenta: left->cyan, right->Button 3
        textButton3.setFocusTraversal(textButton1, textButton1, button4, textButton4);  // Button 3: left->magenta, right->Button 4
        textButton4.setFocusTraversal(textButton2, textButton2, textButton3, button3);  // Button 4: left->Button 3, right->cyan (wrap)

        // ========== Add components to scene ==========
        
        // Add icons and static texts (back layer)
        scene.add(icon1);
        scene.add(icon2);
        scene.add(icon3);
        scene.add(icon4);
        scene.add(text1);
        scene.add(text2);
        scene.add(text3);
        scene.add(text4);
        
        // Add buttons (front layer)
        scene.add(button1);
        scene.add(button2);
        scene.add(button3);
        scene.add(button4);
        scene.add(textButton1);
        scene.add(textButton2);
        scene.add(textButton3);
        scene.add(textButton4);

        System.err.println("HaviTestXlet: initXlet() complete");
        System.err.println("  Scene size: " + sceneWidth + "x" + sceneHeight);
        System.err.println("  === Left side (HGraphicButton test) ===");
        System.err.println("  Icon1 bounds: " + icon1.getBounds());
        System.err.println("  Icon2 bounds: " + icon2.getBounds());
        System.err.println("  Icon3 bounds: " + icon3.getBounds());
        System.err.println("  Icon4 bounds: " + icon4.getBounds());
        System.err.println("  GButton1 bounds: " + button1.getBounds());
        System.err.println("  GButton2 bounds: " + button2.getBounds());
        System.err.println("  GButton3 bounds: " + button3.getBounds());
        System.err.println("  GButton4 bounds: " + button4.getBounds());
        System.err.println("  === Right side (HTextButton test) ===");
        System.err.println("  Text1 bounds: " + text1.getBounds());
        System.err.println("  Text2 bounds: " + text2.getBounds());
        System.err.println("  Text3 bounds: " + text3.getBounds());
        System.err.println("  Text4 bounds: " + text4.getBounds());
        System.err.println("  TButton1 bounds: " + textButton1.getBounds());
        System.err.println("  TButton2 bounds: " + textButton2.getBounds());
        System.err.println("  TButton3 bounds: " + textButton3.getBounds());
        System.err.println("  TButton4 bounds: " + textButton4.getBounds());
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
        System.err.println("  Press ENTER to toggle visibility of the associated icon/text");
        System.err.println("  Left side: HGraphicButtons control HStaticIcons (colored squares)");
        System.err.println("  Right side: HTextButtons control HStaticTexts (text labels)");
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
     * Handle button activation - toggle visibility of associated icon or text
     */
    public void actionPerformed(ActionEvent e) {
        String command = e.getActionCommand();
        System.err.println("HaviTestXlet: actionPerformed(" + command + ")");

        // Handle HGraphicButton actions (toggle icons)
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
        // Handle HTextButton actions (toggle static texts)
        else if ("toggle_text1".equals(command)) {
            boolean newVisibility = !text1.isVisible();
            text1.setVisible(newVisibility);
            System.err.println("HaviTestXlet: Text 1 visibility now: " + newVisibility);
        } else if ("toggle_text2".equals(command)) {
            boolean newVisibility = !text2.isVisible();
            text2.setVisible(newVisibility);
            System.err.println("HaviTestXlet: Text 2 visibility now: " + newVisibility);
        } else if ("toggle_text3".equals(command)) {
            boolean newVisibility = !text3.isVisible();
            text3.setVisible(newVisibility);
            System.err.println("HaviTestXlet: Text 3 visibility now: " + newVisibility);
        } else if ("toggle_text4".equals(command)) {
            boolean newVisibility = !text4.isVisible();
            text4.setVisible(newVisibility);
            System.err.println("HaviTestXlet: Text 4 visibility now: " + newVisibility);
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
