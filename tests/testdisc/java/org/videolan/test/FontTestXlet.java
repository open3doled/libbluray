/*
 * This file is part of libbluray
 * Font Test Xlet - tests different font families, styles, and sizes
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
 * Tests different font rendering options.
 * 
 * Shows text with various:
 * - Font families (SansSerif, Serif, Monospaced)
 * - Font styles (Plain, Bold, Italic, Bold+Italic)
 * - Font sizes (12, 18, 24, 36)
 */
public class FontTestXlet implements Xlet, HActionListener {

    private XletContext context;
    private HScene scene;
    private HStaticText titleLabel;
    private HTextButton menuButton;
    
    // Font configurations to test
    private static final String[] FAMILIES = { "SansSerif", "Serif", "Monospaced" };
    private static final int[] STYLES = { Font.PLAIN, Font.BOLD, Font.ITALIC, Font.BOLD | Font.ITALIC };
    private static final String[] STYLE_NAMES = { "Plain", "Bold", "Italic", "Bold+Italic" };
    private static final int[] SIZES = { 14, 20, 28 };

    public void initXlet(XletContext context) throws XletStateChangeException {
        System.err.println("FontTestXlet: initXlet()");
        this.context = context;

        HSceneFactory factory = HSceneFactory.getInstance();
        scene = factory.getDefaultHScene();
        scene.setLayout(null);

        int sceneWidth = scene.getWidth();
        int sceneHeight = scene.getHeight();
        
        Font titleFont = new Font("SansSerif", Font.BOLD, 28);
        Font menuFont = new Font("SansSerif", Font.PLAIN, 20);
        
        // Title
        titleLabel = new HStaticText("Font Test", 0, 5, sceneWidth, 35);
        titleLabel.setFont(titleFont);
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setHorizontalAlignment(HVisible.HALIGN_CENTER);
        titleLabel.setVerticalAlignment(HVisible.VALIGN_CENTER);
        scene.add(titleLabel);
        
        // Create font samples - 3 families x 4 styles = 12 samples
        int y = 45;
        int lineHeight = 38;
        int labelWidth = 180;
        int sampleWidth = sceneWidth - labelWidth - 40;
        
        for (int f = 0; f < FAMILIES.length; f++) {
            for (int s = 0; s < STYLES.length; s++) {
                String family = FAMILIES[f];
                int style = STYLES[s];
                String styleName = STYLE_NAMES[s];
                
                // Use middle size for the sample
                int size = SIZES[1];
                Font sampleFont = new Font(family, style, size);
                
                // Label
                HStaticText label = new HStaticText(family + " " + styleName + ":", 10, y, labelWidth, lineHeight);
                label.setFont(new Font("SansSerif", Font.PLAIN, 14));
                label.setForeground(Color.GRAY);
                label.setHorizontalAlignment(HVisible.HALIGN_RIGHT);
                label.setVerticalAlignment(HVisible.VALIGN_CENTER);
                scene.add(label);
                
                // Sample text
                HStaticText sample = new HStaticText("The quick brown fox", labelWidth + 20, y, sampleWidth, lineHeight);
                sample.setFont(sampleFont);
                sample.setForeground(Color.WHITE);
                sample.setHorizontalAlignment(HVisible.HALIGN_LEFT);
                sample.setVerticalAlignment(HVisible.VALIGN_CENTER);
                scene.add(sample);
                
                y += lineHeight;
            }
        }
        
        // Add size comparison section
        y += 20;
        HStaticText sizeLabel = new HStaticText("Size comparison (SansSerif Bold):", 10, y, sceneWidth - 20, 25);
        sizeLabel.setFont(new Font("SansSerif", Font.PLAIN, 16));
        sizeLabel.setForeground(Color.YELLOW);
        sizeLabel.setHorizontalAlignment(HVisible.HALIGN_LEFT);
        scene.add(sizeLabel);
        y += 30;
        
        int x = 20;
        for (int i = 0; i < SIZES.length; i++) {
            int size = SIZES[i];
            Font sizeFont = new Font("SansSerif", Font.BOLD, size);
            
            HStaticText sizeText = new HStaticText(size + "pt", x, y, 120, 40);
            sizeText.setFont(sizeFont);
            sizeText.setForeground(Color.WHITE);
            sizeText.setHorizontalAlignment(HVisible.HALIGN_LEFT);
            sizeText.setVerticalAlignment(HVisible.VALIGN_CENTER);
            scene.add(sizeText);
            
            x += 150;
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

        System.err.println("FontTestXlet: initXlet() complete");
    }

    public void startXlet() throws XletStateChangeException {
        System.err.println("FontTestXlet: startXlet()");
        scene.setVisible(true);
        menuButton.requestFocus();
        scene.repaint();
    }

    public void pauseXlet() {
        System.err.println("FontTestXlet: pauseXlet()");
    }

    public void destroyXlet(boolean force) throws XletStateChangeException {
        System.err.println("FontTestXlet: destroyXlet()");
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
            System.err.println("FontTestXlet: Error returning to menu: " + ex.getMessage());
        }
    }
}
