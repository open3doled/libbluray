/*
 * This file is part of libbluray
 * XML parser for MovieObject.bdmv configuration files (HD Cookbook format)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 */

package org.videolan.test;

import java.io.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/**
 * Parser for MovieObject.bdmv XML configuration files using HD Cookbook's format.
 * 
 * This parser supports a subset of the full HD Cookbook movieobject schema.
 * Unsupported features will cause clear error messages rather than
 * being silently ignored.
 * 
 * For BD-J only discs, the MovieObject.bdmv file is essentially empty
 * (no movie objects needed - playback is controlled by BD-J applications).
 * 
 * Supported features:
 *   - Empty movie objects list (BD-J only disc)
 * 
 * Unsupported features (will error):
 *   - Movie objects with navigation commands (HDMV programming)
 *   - Extension data
 */
public class MovieObjectXmlParser {
    
    private Document doc;
    private String sourceFile;
    
    // Parsed values
    private String version = "0200";
    
    public MovieObjectXmlParser(File xmlFile) throws Exception {
        this.sourceFile = xmlFile.getAbsolutePath();
        
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        this.doc = builder.parse(xmlFile);
        doc.getDocumentElement().normalize();
        
        parse();
    }
    
    private void parse() throws Exception {
        Element root = doc.getDocumentElement();
        if (!"movieObjects".equals(root.getTagName())) {
            throw new Exception("Root element must be <movieObjects>, found: " + root.getTagName());
        }
        
        // Check for unsupported features first
        checkUnsupportedFeatures();
        
        // Parse supported elements
        parseVersion();
    }
    
    private void checkUnsupportedFeatures() throws Exception {
        // Movie objects with navigation commands
        NodeList movieObjects = doc.getElementsByTagName("movieObject");
        if (movieObjects.getLength() > 0) {
            for (int i = 0; i < movieObjects.getLength(); i++) {
                Element mo = (Element) movieObjects.item(i);
                NodeList commands = mo.getElementsByTagName("navigationCommands");
                if (commands.getLength() > 0) {
                    // Check if there are actual commands
                    for (int j = 0; j < commands.getLength(); j++) {
                        Element cmdList = (Element) commands.item(j);
                        if (cmdList.hasChildNodes()) {
                            NodeList children = cmdList.getChildNodes();
                            for (int k = 0; k < children.getLength(); k++) {
                                if (children.item(k).getNodeType() == Node.ELEMENT_NODE) {
                                    unsupported("<navigationCommands> (HDMV navigation commands - this is a BD-J only parser)");
                                }
                            }
                        }
                    }
                }
                // Also check for command element directly under movieObject
                NodeList directCommands = mo.getElementsByTagName("command");
                if (directCommands.getLength() > 0) {
                    unsupported("<command> elements (HDMV navigation commands)");
                }
            }
            // If we get here, movie objects exist but have no commands - that's unusual but technically allowed
            // However, for simplicity, we'll just warn and generate an empty file anyway
            System.out.println("Warning: <movieObject> elements found but ignored (BD-J only disc)");
        }
        
        // Extension data
        if (hasElement("extensionData")) {
            Element ext = getFirstElement("extensionData");
            if (ext != null && ext.hasChildNodes()) {
                NodeList children = ext.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node child = children.item(i);
                    if (child.getNodeType() == Node.ELEMENT_NODE) {
                        unsupported("<extensionData>");
                    }
                }
            }
        }
    }
    
    private void unsupported(String feature) throws Exception {
        throw new Exception("Unsupported feature in " + sourceFile + ": " + feature + 
            "\n  This minimal parser only supports BD-J only test discs (no HDMV movie objects)." +
            "\n  For full MovieObject.bdmv support, use HD Cookbook tools directly.");
    }
    
    private void parseVersion() {
        String v = getElementText("version");
        if (v != null) {
            version = v.replace("V_", "");
            // Ensure 4 chars
            while (version.length() < 4) version = "0" + version;
        }
    }
    
    // DOM helpers
    
    private boolean hasElement(String tagName) {
        return doc.getElementsByTagName(tagName).getLength() > 0;
    }
    
    private String getElementText(String tagName) {
        NodeList list = doc.getElementsByTagName(tagName);
        if (list.getLength() > 0) {
            return list.item(0).getTextContent().trim();
        }
        return null;
    }
    
    private Element getFirstElement(String tagName) {
        NodeList list = doc.getElementsByTagName(tagName);
        if (list.getLength() > 0) {
            return (Element) list.item(0);
        }
        return null;
    }
    
    // Binary generation
    
    public byte[] generate() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        
        // Header
        out.writeBytes("MOBJ");
        out.writeBytes(version);
        
        // Extension data start address (4 bytes) - 0 = no extension
        out.writeInt(0);
        
        // Reserved (24 bytes)
        for (int i = 0; i < 24; i++) {
            out.writeByte(0);
        }
        
        // Movie Objects section
        // Length (4 bytes) - just header: 4 bytes reserved + 2 bytes num_objects
        out.writeInt(6);
        
        // Reserved (4 bytes)
        out.writeInt(0);
        
        // Number of movie objects (2 bytes) - 0 for BD-J only disc
        out.writeShort(0);
        
        return buffer.toByteArray();
    }
    
    public void writeToFile(String outputPath) throws Exception {
        byte[] data = generate();
        FileOutputStream fos = new FileOutputStream(outputPath);
        fos.write(data);
        fos.close();
        System.out.println("Wrote MovieObject.bdmv: " + outputPath + " (" + data.length + " bytes)");
    }
    
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: MovieObjectXmlParser <input.xml> <output.bdmv>");
            System.err.println();
            System.err.println("Converts HD Cookbook format movieobject XML to binary MovieObject.bdmv file.");
            System.err.println("Only BD-J only discs are supported (no HDMV navigation commands).");
            System.exit(1);
        }
        
        File inputFile = new File(args[0]);
        String outputPath = args[1];
        
        MovieObjectXmlParser parser = new MovieObjectXmlParser(inputFile);
        parser.writeToFile(outputPath);
    }
}
