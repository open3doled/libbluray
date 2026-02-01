/*
 * This file is part of libbluray
 * XML parser for index.bdmv configuration files (HD Cookbook format)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 */

package org.videolan.test;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/**
 * Parser for index.bdmv XML configuration files using HD Cookbook's format.
 * 
 * This parser supports a subset of the full HD Cookbook index schema.
 * Unsupported features will cause clear error messages rather than
 * being silently ignored.
 * 
 * Supported features:
 *   - BD-J objects for firstPlayback, topMenu, and titles
 *   - Multiple titles (for test disc menu navigation)
 * 
 * Unsupported features (will error):
 *   - HDMV (movie) objects
 *   - appInfo (video format, frame rate, etc.)
 *   - extensionData
 */
public class IndexXmlParser {
    
    /**
     * Represents a title entry with its BDJO file name and access type.
     */
    private static class TitleEntry {
        String bdjoFileName;
        int accessType;
        
        TitleEntry(String bdjoFileName, int accessType) {
            this.bdjoFileName = bdjoFileName;
            this.accessType = accessType;
        }
    }
    
    private Document doc;
    private String sourceFile;
    
    // Parsed values
    private String version = "0200";
    private String firstPlaybackBdjo = null;
    private String topMenuBdjo = null;
    private List<TitleEntry> titles = new ArrayList<TitleEntry>();
    
    public IndexXmlParser(File xmlFile) throws Exception {
        this.sourceFile = xmlFile.getAbsolutePath();
        
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        this.doc = builder.parse(xmlFile);
        doc.getDocumentElement().normalize();
        
        parse();
    }
    
    private void parse() throws Exception {
        Element root = doc.getDocumentElement();
        if (!"index".equals(root.getTagName())) {
            throw new Exception("Root element must be <index>, found: " + root.getTagName());
        }
        
        // Check for unsupported features first
        checkUnsupportedFeatures();
        
        // Parse supported elements
        parseVersion();
        parseIndexes();
    }
    
    private void checkUnsupportedFeatures() throws Exception {
        // HDMV objects
        if (hasElement("hdmvObject")) {
            unsupported("<hdmvObject> (HDMV/movie objects - only BD-J objects supported)");
        }
        
        // appInfo
        if (hasElement("appInfo")) {
            Element appInfo = getFirstElement("appInfo");
            // Check if it has any meaningful content
            if (appInfo != null && appInfo.hasChildNodes()) {
                NodeList children = appInfo.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node child = children.item(i);
                    if (child.getNodeType() == Node.ELEMENT_NODE) {
                        unsupported("<appInfo> (video format, frame rate settings)");
                    }
                }
            }
        }
        
        // extensionData
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
        
        // Padding data
        if (hasElement("paddingN1Data") || hasElement("paddingN2Data") || hasElement("paddingN3Data")) {
            unsupported("padding data elements");
        }
    }
    
    private void unsupported(String feature) throws Exception {
        throw new Exception("Unsupported feature in " + sourceFile + ": " + feature + 
            "\n  This minimal parser only supports basic BD-J test discs." +
            "\n  For full index.bdmv support, use HD Cookbook tools directly.");
    }
    
    private void parseVersion() {
        String v = getElementText("version");
        if (v != null) {
            version = v.replace("V_", "");
            // Ensure 4 chars
            while (version.length() < 4) version = "0" + version;
        }
    }
    
    private void parseIndexes() throws Exception {
        Element indexes = getFirstElement("indexes");
        if (indexes == null) {
            throw new Exception("Missing required <indexes> element in " + sourceFile);
        }
        
        // First Playback
        Element fp = getFirstChildElement(indexes, "firstPlayback");
        if (fp != null) {
            Element bdjObj = getFirstChildElement(fp, "bdjObject");
            if (bdjObj != null) {
                firstPlaybackBdjo = getChildText(bdjObj, "bdjoFileName");
            } else {
                // Check for HDMV object
                if (getFirstChildElement(fp, "hdmvObject") != null) {
                    unsupported("<hdmvObject> in <firstPlayback>");
                }
            }
        }
        
        // Top Menu
        Element tm = getFirstChildElement(indexes, "topMenu");
        if (tm != null) {
            Element bdjObj = getFirstChildElement(tm, "bdjObject");
            if (bdjObj != null) {
                topMenuBdjo = getChildText(bdjObj, "bdjoFileName");
            } else {
                if (getFirstChildElement(tm, "hdmvObject") != null) {
                    unsupported("<hdmvObject> in <topMenu>");
                }
            }
        }
        
        // Titles - parse all title elements
        Element titlesElement = getFirstChildElement(indexes, "titles");
        if (titlesElement != null) {
            NodeList titleNodes = titlesElement.getChildNodes();
            for (int i = 0; i < titleNodes.getLength(); i++) {
                Node titleNode = titleNodes.item(i);
                if (titleNode.getNodeType() == Node.ELEMENT_NODE && "title".equals(titleNode.getNodeName())) {
                    Element title = (Element) titleNode;
                    String bdjoFileName = null;
                    int accessType = 1;  // Default V_01
                    
                    Element bdjObj = getFirstChildElement(title, "bdjObject");
                    if (bdjObj != null) {
                        bdjoFileName = getChildText(bdjObj, "bdjoFileName");
                    } else {
                        // Check for indexObject (generic) or hdmvObject
                        Element indexObj = getFirstChildElement(title, "indexObject");
                        if (indexObj != null) {
                            // Could be either type, check for bdjoFileName
                            String bdjo = getChildText(indexObj, "bdjoFileName");
                            if (bdjo != null) {
                                bdjoFileName = bdjo;
                            } else {
                                unsupported("<indexObject> without <bdjoFileName> (HDMV objects)");
                            }
                        }
                    }
                    
                    String accessTypeStr = getChildText(title, "titleAccessType");
                    if (accessTypeStr != null) {
                        accessType = parseTitleAccessType(accessTypeStr);
                    }
                    
                    if (bdjoFileName != null) {
                        titles.add(new TitleEntry(bdjoFileName, accessType));
                    }
                }
            }
        }
        
        // Validate we have at least first playback
        if (firstPlaybackBdjo == null) {
            throw new Exception("Missing <firstPlayback><bdjObject><bdjoFileName> in " + sourceFile);
        }
        
        System.out.println("Parsed " + titles.size() + " title(s) from " + sourceFile);
    }
    
    private int parseTitleAccessType(String type) {
        if ("V_00".equals(type)) return 0;
        if ("V_01".equals(type)) return 1;
        if ("V_10".equals(type)) return 2;
        if ("V_11".equals(type)) return 3;
        return 1;  // Default to V_01
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
    
    private Element getFirstChildElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                return (Element) child;
            }
        }
        return null;
    }
    
    private String getChildText(Element parent, String tagName) {
        Element child = getFirstChildElement(parent, tagName);
        if (child != null) {
            return child.getTextContent().trim();
        }
        return null;
    }
    
    // Binary generation
    
    public byte[] generate() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        
        // AppInfoBDMV is 38 bytes (length(4) + reserved(1) + video/frame(1) + reserved(32))
        // Header ends at offset 40 (0x28), AppInfo is at 40, Indexes start after AppInfo
        // Maintainer says indexes should be at 0x4e (78) or later
        int appInfoSize = 38;
        int indexesStartAddress = 40 + appInfoSize;  // 78 (0x4E)
        
        // === Header (40 bytes) ===
        out.writeBytes("INDX");
        out.writeBytes(version);
        
        // Indexes start address (4 bytes)
        out.writeInt(indexesStartAddress);
        
        // Extension data start address (4 bytes) - 0 = no extension
        out.writeInt(0);
        
        // Reserved (24 bytes)
        for (int i = 0; i < 24; i++) {
            out.writeByte(0);
        }
        
        // === AppInfoBDMV (38 bytes) at offset 0x28 ===
        out.writeInt(34);  // length of AppInfo data (excludes this length field)
        out.writeByte(0);  // reserved
        // video_format (4 bits) = 0, frame_rate (4 bits) = 0
        out.writeByte(0);
        // content_provider_data (32 bytes) - descriptive string for identification
        String contentProviderData = "libbluray test disc-------------";
        out.writeBytes(contentProviderData);
        
        // === Indexes section (at offset 0x4E) ===
        int indexesStart = buffer.size();
        out.writeInt(0);  // Placeholder for length
        
        // First Playback
        writeFirstPlayback(out, firstPlaybackBdjo);
        
        // Top Menu
        writeTopMenu(out, topMenuBdjo != null ? topMenuBdjo : firstPlaybackBdjo);
        
        // Number of titles
        out.writeShort(titles.size());
        
        // Title entries
        for (TitleEntry title : titles) {
            writeTitle(out, title.bdjoFileName, title.accessType);
        }
        
        // Calculate and update indexes length
        byte[] data = buffer.toByteArray();
        int indexesLength = data.length - indexesStart - 4;
        data[indexesStart] = (byte) ((indexesLength >> 24) & 0xFF);
        data[indexesStart + 1] = (byte) ((indexesLength >> 16) & 0xFF);
        data[indexesStart + 2] = (byte) ((indexesLength >> 8) & 0xFF);
        data[indexesStart + 3] = (byte) (indexesLength & 0xFF);
        
        return data;
    }
    
    private void writeFirstPlayback(DataOutputStream out, String bdjoFileName) throws IOException {
        // first_playback_type (2 bits) = 10 (BD-J = type 2)
        // reserved (30 bits)
        out.writeInt(0x80000000);
        
        // bdj_first_playback:
        // playback_type (2 bits) = 11 (BD-J Interactive) = 0xC000
        // reserved (14 bits)
        out.writeShort(0xC000);
        
        // name (5 bytes) = BDJO file name (e.g., "00000")
        writeBdjoName(out, bdjoFileName);
        
        // Reserved (1 byte)
        out.writeByte(0);
    }
    
    private void writeTopMenu(DataOutputStream out, String bdjoFileName) throws IOException {
        // top_menu_type (2 bits) = 10 (BD-J = type 2)
        // reserved (30 bits)
        out.writeInt(0x80000000);
        
        // bdj_top_menu:
        // playback_type (2 bits) = 11 (BD-J Interactive) = 0xC000
        // reserved (14 bits)
        out.writeShort(0xC000);
        
        // name (5 bytes) = BDJO file name (e.g., "00000")
        writeBdjoName(out, bdjoFileName);
        
        // Reserved (1 byte)
        out.writeByte(0);
    }
    
    private void writeTitle(DataOutputStream out, String bdjoFileName, int accessType) throws IOException {
        // object_type (2 bits) = 10 (BD-J = type 2)
        // title_access_type (2 bits)
        // reserved (28 bits)
        int header = 0x80000000;  // BD-J object type (10 in top 2 bits = 2)
        header |= (accessType & 0x03) << 28;  // access type in bits 29-28
        out.writeInt(header);
        
        // bdj_title:
        // playback_type (2 bits) = 11 (BD-J Interactive) = 0xC000
        // reserved (14 bits)
        out.writeShort(0xC000);
        
        // name (5 bytes) = BDJO file name (e.g., "00000")
        writeBdjoName(out, bdjoFileName);
        
        // Reserved (1 byte)
        out.writeByte(0);
    }
    
    private void writeBdjoName(DataOutputStream out, String bdjoFileName) throws IOException {
        // Get the 5-character BDJO name (strip .bdjo extension if present)
        String name = bdjoFileName;
        if (name == null) name = "00000";
        name = name.replace(".bdjo", "");
        // Pad or truncate to exactly 5 characters
        while (name.length() < 5) name = "0" + name;
        if (name.length() > 5) name = name.substring(0, 5);
        out.writeBytes(name);
    }
    
    private int parseBdjoId(String bdjoFileName) {
        if (bdjoFileName == null) return 0;
        // Strip any extension and parse as number
        String name = bdjoFileName.replace(".bdjo", "");
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    public void writeToFile(String outputPath) throws Exception {
        byte[] data = generate();
        FileOutputStream fos = new FileOutputStream(outputPath);
        fos.write(data);
        fos.close();
        System.out.println("Wrote index.bdmv: " + outputPath + " (" + data.length + " bytes)");
    }
    
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: IndexXmlParser <input.xml> <output.bdmv>");
            System.err.println();
            System.err.println("Converts HD Cookbook format index XML to binary index.bdmv file.");
            System.err.println("Only a subset of features is supported - unsupported features will error.");
            System.exit(1);
        }
        
        File inputFile = new File(args[0]);
        String outputPath = args[1];
        
        IndexXmlParser parser = new IndexXmlParser(inputFile);
        parser.writeToFile(outputPath);
    }
}
