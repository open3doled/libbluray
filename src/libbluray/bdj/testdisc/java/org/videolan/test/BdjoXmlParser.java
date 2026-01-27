/*
 * This file is part of libbluray
 * XML parser for BDJO configuration files (HD Cookbook format)
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
 * Parser for BDJO XML configuration files using HD Cookbook's format.
 * 
 * This parser supports a subset of the full HD Cookbook BDJO schema.
 * Unsupported features will cause clear error messages rather than
 * being silently ignored.
 * 
 * Supported features:
 *   - Single application per BDJO
 *   - JAR cache entries (type=1)
 *   - Basic terminal info
 *   - Access to all playlists
 * 
 * Unsupported features (will error):
 *   - Multiple applications
 *   - Directory cache entries (type=2)
 *   - App names, parameters, icons
 *   - Mouse support, 3D mode, frame rate hints
 *   - Explicit playlist file names
 */
public class BdjoXmlParser {
    
    private Document doc;
    private String sourceFile;
    
    // Parsed values
    private String version = "0200";
    private String defaultFontFile = "*****";
    private int initialHaviConfig = 7;  // HD_1920_1080
    private boolean menuCallMask = false;
    private boolean titleSearchMask = false;
    
    private String cacheEntryName = "00000";
    private String cacheEntryLanguage = "*.*";
    
    private int controlCode = 1;  // AUTOSTART
    private int organizationId = 0x7fff0001;
    private int applicationId = 0x4000;
    private int applicationType = 1;  // BD-J
    
    private int priority = 128;
    private int binding = 3;  // TITLE_BOUND_DISC_BOUND
    private int visibility = 3;  // V_11
    private String initialClassName = "";
    private String baseDirectory = "";
    private String classpathExtension = "";
    private int profileNumber = 1;
    private int majorVersion = 1;
    private int minorVersion = 0;
    private int microVersion = 0;
    
    private boolean accessToAllFlag = true;
    private boolean autostartFirstPlayListFlag = false;
    
    private int keyInterestTable = 0;
    private String fileAccessInfo = ".";
    
    public BdjoXmlParser(File xmlFile) throws Exception {
        this.sourceFile = xmlFile.getAbsolutePath();
        
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        this.doc = builder.parse(xmlFile);
        doc.getDocumentElement().normalize();
        
        parse();
    }
    
    private void parse() throws Exception {
        Element root = doc.getDocumentElement();
        if (!"bdjo".equals(root.getTagName())) {
            throw new Exception("Root element must be <bdjo>, found: " + root.getTagName());
        }
        
        // Check for unsupported features first
        checkUnsupportedFeatures();
        
        // Parse supported elements
        parseVersion();
        parseTerminalInfo();
        parseAppCacheInfo();
        parseApplicationManagementTable();
        parseTableOfAccessiblePlayLists();
        parseKeyInterestTable();
        parseFileAccessInfo();
    }
    
    private void checkUnsupportedFeatures() throws Exception {
        // Multiple applications
        NodeList apps = doc.getElementsByTagName("applications");
        if (apps.getLength() > 1) {
            unsupported("Multiple <applications> elements (only single-app BDJOs supported)");
        }
        
        // Directory cache entries
        NodeList entries = doc.getElementsByTagName("entries");
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            String type = getChildText(entry, "type");
            if ("2".equals(type)) {
                unsupported("<entries><type>2</type> (directory cache entries)");
            }
        }
        
        // App names
        if (hasElement("names")) {
            unsupported("<names> (multi-language application names)");
        }
        
        // Parameters
        if (hasElement("parameters")) {
            unsupported("<parameters> (xlet parameters)");
        }
        
        // Icons
        String iconLocator = getElementText("iconLocator");
        if (iconLocator != null && !iconLocator.isEmpty()) {
            unsupported("<iconLocator> (application icons)");
        }
        String iconFlags = getElementText("iconFlags");
        if (iconFlags != null && !"0x0".equals(iconFlags) && !"0".equals(iconFlags)) {
            unsupported("<iconFlags> with non-zero value");
        }
        
        // Mouse support
        if (hasElement("mouseSupported") || hasElement("mouseInterest")) {
            String mouseSupported = getElementText("mouseSupported");
            String mouseInterest = getElementText("mouseInterest");
            if ("true".equals(mouseSupported) || "true".equals(mouseInterest)) {
                unsupported("<mouseSupported>/<mouseInterest> (mouse support)");
            }
        }
        
        // 3D output mode
        if (hasElement("initialOutputMode")) {
            String mode = getElementText("initialOutputMode");
            if (mode != null && !"0".equals(mode)) {
                unsupported("<initialOutputMode> with non-2D value");
            }
        }
        
        // Frame rate hints
        if (hasElement("initialFrameRate")) {
            unsupported("<initialFrameRate> (frame rate hints)");
        }
        
        // Explicit playlist file names
        if (hasElement("playListFileNames")) {
            unsupported("<playListFileNames> (explicit playlist lists - use accessToAllFlag instead)");
        }
    }
    
    private void unsupported(String feature) throws Exception {
        throw new Exception("Unsupported feature in " + sourceFile + ": " + feature + 
            "\n  This minimal parser only supports basic BD-J test discs." +
            "\n  For full BDJO support, use HD Cookbook tools directly.");
    }
    
    private void parseVersion() {
        String v = getElementText("version");
        if (v != null) {
            if ("V_0200".equals(v)) {
                version = "0200";
            } else if ("V_0100".equals(v)) {
                version = "0100";
            } else if ("V_0300".equals(v)) {
                version = "0300";
            } else {
                version = v.replace("V_", "");
            }
        }
    }
    
    private void parseTerminalInfo() {
        Element ti = getFirstElement("terminalInfo");
        if (ti == null) return;
        
        String font = getChildText(ti, "defaultFontFile");
        if (font != null) {
            defaultFontFile = font;
        }
        
        String config = getChildText(ti, "initialHaviConfig");
        if (config != null) {
            initialHaviConfig = parseHaviConfig(config);
        }
        
        String menuMask = getChildText(ti, "menuCallMask");
        if (menuMask != null) {
            menuCallMask = "true".equalsIgnoreCase(menuMask);
        }
        
        String titleMask = getChildText(ti, "titleSearchMask");
        if (titleMask != null) {
            titleSearchMask = "true".equalsIgnoreCase(titleMask);
        }
    }
    
    private int parseHaviConfig(String config) {
        if ("HD_1920_1080".equals(config) || "UHD_1920_1080".equals(config)) return 7;
        if ("HD_1280_720".equals(config)) return 6;
        if ("QHD_960_540".equals(config)) return 5;
        if ("SD_60HZ_720_480".equals(config)) return 3;
        if ("SD_50HZ_720_576".equals(config)) return 4;
        if ("SD".equals(config)) return 1;
        return 7;  // Default to 1080p
    }
    
    private void parseAppCacheInfo() {
        Element entries = getFirstElement("entries");
        if (entries == null) return;
        
        String name = getChildText(entries, "name");
        if (name != null) {
            cacheEntryName = name;
        }
        
        String lang = getChildText(entries, "language");
        if (lang != null) {
            cacheEntryLanguage = lang;
        }
    }
    
    private void parseApplicationManagementTable() throws Exception {
        Element apps = getFirstElement("applications");
        if (apps == null) {
            throw new Exception("Missing required <applications> element in " + sourceFile);
        }
        
        String cc = getChildText(apps, "controlCode");
        if (cc != null) {
            controlCode = parseHexOrInt(cc);
        }
        
        String orgId = getChildText(apps, "organizationId");
        if (orgId != null) {
            organizationId = parseHexOrInt(orgId);
        }
        
        String appId = getChildText(apps, "applicationId");
        if (appId != null) {
            applicationId = parseHexOrInt(appId);
        }
        
        String type = getChildText(apps, "type");
        if (type != null) {
            applicationType = parseHexOrInt(type);
        }
        
        Element appDesc = getFirstChildElement(apps, "applicationDescriptor");
        if (appDesc != null) {
            parseApplicationDescriptor(appDesc);
        }
    }
    
    private void parseApplicationDescriptor(Element appDesc) throws Exception {
        String p = getChildText(appDesc, "priority");
        if (p != null) {
            priority = Integer.parseInt(p);
        }
        
        String b = getChildText(appDesc, "binding");
        if (b != null) {
            binding = parseBinding(b);
        }
        
        String v = getChildText(appDesc, "visibility");
        if (v != null) {
            visibility = parseVisibility(v);
        }
        
        String ic = getChildText(appDesc, "initialClassName");
        if (ic != null) {
            initialClassName = ic;
        } else {
            throw new Exception("Missing required <initialClassName> in " + sourceFile);
        }
        
        String bd = getChildText(appDesc, "baseDirectory");
        if (bd != null) {
            baseDirectory = bd;
        }
        
        String cp = getChildText(appDesc, "classpathExtension");
        if (cp != null) {
            classpathExtension = cp;
        }
        
        Element profiles = getFirstChildElement(appDesc, "profiles");
        if (profiles != null) {
            parseProfile(profiles);
        }
    }
    
    private void parseProfile(Element profiles) {
        String p = getChildText(profiles, "profile");
        if (p != null) {
            profileNumber = Integer.parseInt(p);
        }
        
        String major = getChildText(profiles, "majorVersion");
        if (major != null) {
            majorVersion = Integer.parseInt(major);
        }
        
        String minor = getChildText(profiles, "minorVersion");
        if (minor != null) {
            minorVersion = Integer.parseInt(minor);
        }
        
        String micro = getChildText(profiles, "microVersion");
        if (micro != null) {
            microVersion = Integer.parseInt(micro);
        }
    }
    
    private int parseBinding(String b) {
        if ("TITLE_BOUND_DISC_BOUND".equals(b)) return 0;
        if ("TITLE_UNBOUND_DISC_BOUND".equals(b)) return 2;
        if ("TITLE_UNBOUND_DISC_UNBOUND".equals(b)) return 3;
        return 0;
    }
    
    private int parseVisibility(String v) {
        if ("V_11".equals(v)) return 3;
        if ("V_10".equals(v)) return 2;
        if ("V_01".equals(v)) return 1;
        if ("V_00".equals(v)) return 0;
        return 3;
    }
    
    private void parseTableOfAccessiblePlayLists() {
        Element tapl = getFirstElement("tableOfAccessiblePlayLists");
        if (tapl == null) return;
        
        String ata = getChildText(tapl, "accessToAllFlag");
        if (ata != null) {
            accessToAllFlag = "true".equalsIgnoreCase(ata);
        }
        
        String auto = getChildText(tapl, "autostartFirstPlayListFlag");
        if (auto != null) {
            autostartFirstPlayListFlag = "true".equalsIgnoreCase(auto);
        }
    }
    
    private void parseKeyInterestTable() {
        String kit = getElementText("keyInterestTable");
        if (kit != null) {
            keyInterestTable = parseHexOrInt(kit);
        }
    }
    
    private void parseFileAccessInfo() {
        String fai = getElementText("fileAccessInfo");
        if (fai != null) {
            fileAccessInfo = fai;
        }
    }
    
    private int parseHexOrInt(String s) {
        if (s == null) return 0;
        s = s.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            return Integer.parseUnsignedInt(s.substring(2), 16);
        }
        return Integer.parseInt(s);
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
        NodeList list = parent.getElementsByTagName(tagName);
        if (list.getLength() > 0) {
            return (Element) list.item(0);
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
        
        // Generate each section
        byte[] terminalInfo = generateTerminalInfo();
        byte[] appCache = generateAppCacheInfo();
        byte[] playlists = generateAccessiblePlaylists();
        byte[] appTable = generateAppManagementTable();
        byte[] keyInterest = generateKeyInterestTable();
        byte[] fileAccess = generateFileAccessInfo();
        
        // Calculate offsets (after 48-byte header + address table)
        int baseOffset = 48;
        int terminalInfoOffset = baseOffset;
        int appCacheOffset = terminalInfoOffset + terminalInfo.length;
        int playlistsOffset = appCacheOffset + appCache.length;
        int appTableOffset = playlistsOffset + playlists.length;
        int keyInterestOffset = appTableOffset + appTable.length;
        int fileAccessOffset = keyInterestOffset + keyInterest.length;
        
        // Write header
        out.writeBytes("BDJO");
        out.writeBytes(version);
        
        // Write address table
        out.writeInt(terminalInfoOffset);
        out.writeInt(appCacheOffset);
        out.writeInt(playlistsOffset);
        out.writeInt(appTableOffset);
        out.writeInt(keyInterestOffset);
        out.writeInt(fileAccessOffset);
        out.writeInt(0);  // Reserved
        out.writeInt(0);
        out.writeInt(0);
        out.writeInt(0);
        
        // Write data sections
        out.write(terminalInfo);
        out.write(appCache);
        out.write(playlists);
        out.write(appTable);
        out.write(keyInterest);
        out.write(fileAccess);
        
        return buffer.toByteArray();
    }
    
    private byte[] generateTerminalInfo() throws IOException {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(content);
        
        // Pad font file to 5 chars
        String font = defaultFontFile;
        while (font.length() < 5) font = font + "*";
        out.writeBytes(font.substring(0, 5));
        
        // initial_havi_config_id (4 bits)
        // menu_call_mask (1 bit)
        // title_search_mask (1 bit)
        // reserved (2 bits)
        int flags = (initialHaviConfig & 0x0F) << 4;
        if (menuCallMask) flags |= 0x08;
        if (titleSearchMask) flags |= 0x04;
        out.writeByte(flags);
        
        // Padding (4 bytes)
        out.writeInt(0);
        
        byte[] contentBytes = content.toByteArray();
        
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        DataOutputStream resultOut = new DataOutputStream(result);
        resultOut.writeInt(contentBytes.length);
        resultOut.write(contentBytes);
        
        return result.toByteArray();
    }
    
    private byte[] generateAppCacheInfo() throws IOException {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(content);
        
        out.writeByte(1);  // num_item
        out.writeByte(0);  // padding
        
        // Cache entry
        out.writeByte(1);  // type = JAR
        
        // Pad name to 5 chars
        String name = cacheEntryName;
        while (name.length() < 5) name = name + " ";
        out.writeBytes(name.substring(0, 5));
        
        // Pad language to 3 chars
        String lang = cacheEntryLanguage;
        while (lang.length() < 3) lang = lang + " ";
        out.writeBytes(lang.substring(0, 3));
        
        // padding (3 bytes)
        out.writeByte(0);
        out.writeByte(0);
        out.writeByte(0);
        
        byte[] contentBytes = content.toByteArray();
        
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        DataOutputStream resultOut = new DataOutputStream(result);
        resultOut.writeInt(contentBytes.length);
        resultOut.write(contentBytes);
        
        return result.toByteArray();
    }
    
    private byte[] generateAccessiblePlaylists() throws IOException {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(content);
        
        // num_pl (11 bits) = 0
        // access_to_all_flag (1 bit)
        // autostart_first_playlist_flag (1 bit)
        // padding (19 bits)
        int flags = 0;
        if (accessToAllFlag) flags |= 0x0010;
        if (autostartFirstPlayListFlag) flags |= 0x0008;
        out.writeShort(flags);
        out.writeShort(0);  // padding
        
        byte[] contentBytes = content.toByteArray();
        
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        DataOutputStream resultOut = new DataOutputStream(result);
        resultOut.writeInt(contentBytes.length);
        resultOut.write(contentBytes);
        
        return result.toByteArray();
    }
    
    private byte[] generateAppManagementTable() throws IOException {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(content);
        
        out.writeByte(1);  // num_app
        out.writeByte(0);  // padding
        
        // Application entry
        out.writeByte(controlCode);
        out.writeByte((applicationType & 0x0F) << 4);
        out.writeInt(organizationId);
        out.writeShort(applicationId);
        
        // Application descriptor
        writeApplicationDescriptor(out);
        
        byte[] contentBytes = content.toByteArray();
        
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        DataOutputStream resultOut = new DataOutputStream(result);
        resultOut.writeInt(contentBytes.length);
        resultOut.write(contentBytes);
        
        return result.toByteArray();
    }
    
    private void writeApplicationDescriptor(DataOutputStream out) throws IOException {
        // descriptor_tag (8 bytes) - reserved
        out.writeLong(0);
        // descriptor_length (2 bytes) - will be ignored, actual parsing uses structure
        out.writeShort(0);
        
        // num_profile (4 bits) = 1, padding (12 bits)
        out.writeShort(0x1000);
        
        // Profile
        out.writeShort(profileNumber);
        out.writeByte(majorVersion);
        out.writeByte(minorVersion);
        out.writeByte(microVersion);
        out.writeByte(0);  // padding
        
        // priority
        out.writeByte(priority);
        
        // binding (2 bits), visibility (2 bits), padding (4 bits)
        int bv = ((binding & 0x03) << 6) | ((visibility & 0x03) << 4);
        out.writeByte(bv);
        
        // Application names - minimal "Test" name
        byte[] appName = "Test".getBytes("UTF-8");
        int nameDataLen = 3 + 1 + appName.length;  // lang(3) + len(1) + name
        out.writeShort(nameDataLen);
        out.writeBytes("eng");
        out.writeByte(appName.length);
        out.write(appName);
        if ((nameDataLen & 1) != 0) {
            out.writeByte(0);  // word align
        }
        
        // icon_locator (length + string)
        out.writeByte(0);
        out.writeByte(0);  // word align
        
        // icon_flags
        out.writeShort(0);
        
        // base_dir
        byte[] baseDirBytes = baseDirectory.getBytes("UTF-8");
        out.writeByte(baseDirBytes.length);
        out.write(baseDirBytes);
        if ((baseDirBytes.length & 1) == 0) {
            out.writeByte(0);  // word align
        }
        
        // classpath_extension
        byte[] cpBytes = classpathExtension.getBytes("UTF-8");
        out.writeByte(cpBytes.length);
        if (cpBytes.length > 0) {
            out.write(cpBytes);
        }
        if ((cpBytes.length & 1) == 0) {
            out.writeByte(0);  // word align
        }
        
        // initial_class
        byte[] initClassBytes = initialClassName.getBytes("UTF-8");
        out.writeByte(initClassBytes.length);
        out.write(initClassBytes);
        if ((initClassBytes.length & 1) == 0) {
            out.writeByte(0);  // word align
        }
        
        // parameters
        out.writeByte(0);  // no parameters
        out.writeByte(0);  // word align
    }
    
    private byte[] generateKeyInterestTable() throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(result);
        out.writeInt(keyInterestTable);
        return result.toByteArray();
    }
    
    private byte[] generateFileAccessInfo() throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(result);
        byte[] path = fileAccessInfo.getBytes("UTF-8");
        out.writeShort(path.length);
        out.write(path);
        return result.toByteArray();
    }
    
    public void writeToFile(String outputPath) throws Exception {
        byte[] data = generate();
        FileOutputStream fos = new FileOutputStream(outputPath);
        fos.write(data);
        fos.close();
        System.out.println("Wrote BDJO file: " + outputPath + " (" + data.length + " bytes)");
    }
    
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: BdjoXmlParser <input.xml> <output.bdjo>");
            System.err.println();
            System.err.println("Converts HD Cookbook format BDJO XML to binary .bdjo file.");
            System.err.println("Only a subset of features is supported - unsupported features will error.");
            System.exit(1);
        }
        
        File inputFile = new File(args[0]);
        String outputPath = args[1];
        
        BdjoXmlParser parser = new BdjoXmlParser(inputFile);
        parser.writeToFile(outputPath);
    }
}
