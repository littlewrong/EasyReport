package cn.easyreport.console.importexcel;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/** Validates this upload without changing POI's process-wide ZIP settings. */
final class ExcelUploadValidator {
    private static final long MAX_ENTRY_BYTES = 10L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 50L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 256;
    private static final byte[] OLE_SIGNATURE = {
        (byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1
    };

    private ExcelUploadValidator() { }

    static String validateName(String name) throws IOException {
        if (name == null || name.length() == 0 || name.length() > 255
                || !name.equals(name.trim()) || name.endsWith(".")) {
            throw invalidUpload();
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isISOControl(ch) || "/\\:*?\"<>|".indexOf(ch) >= 0) {
                throw invalidUpload();
            }
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xls") && !lower.endsWith(".xlsx")) {
            throw invalidUpload();
        }
        return lower;
    }

    static void validate(Path file, String name) throws Exception {
        byte[] signature = new byte[8];
        try (InputStream in = Files.newInputStream(file)) {
            int offset = 0;
            while (offset < signature.length) {
                int count = in.read(signature, offset, signature.length - offset);
                if (count < 0) throw invalidUpload();
                offset += count;
            }
        }
        if (name.endsWith(".xls")) {
            for (int i = 0; i < signature.length; i++) {
                if (signature[i] != OLE_SIGNATURE[i]) throw invalidUpload();
            }
        } else {
            if (signature[0] != 'P' || signature[1] != 'K' || signature[2] != 3 || signature[3] != 4) {
                throw invalidUpload();
            }
            validateZip(file);
        }
    }

    private static void validateZip(Path file) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        XMLReader reader = factory.newSAXParser().getXMLReader();
        reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("External entities are forbidden."); });
        ContentTypes types = new ContentTypes();
        List<Relationship> relationships = new ArrayList<Relationship>();
        XmlLimits xmlLimits = new XmlLimits(types, relationships);
        reader.setContentHandler(xmlLimits);
        reader.setErrorHandler(xmlLimits);
        long[] expandedBytes = { 0 };
        Set<String> names = new HashSet<String>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (names.size() >= MAX_ZIP_ENTRIES || !names.add(name)
                        || name.startsWith("/") || name.contains("\\") || name.contains("../")) {
                    throw invalidUpload();
                }
                EntryInputStream limited = new EntryInputStream(zip, expandedBytes);
                if (name.endsWith(".xml") || name.endsWith(".rels")) {
                    xmlLimits.entryName = name;
                    reader.parse(new InputSource(limited));
                }
                while (limited.read(buffer) != -1) { /* Count all bytes, including non-XML parts. */ }
                if (limited.count > 100 * 1024 && entry.getCompressedSize() >= 0
                        && (double) entry.getCompressedSize() / limited.count < 0.01d) {
                    throw invalidUpload();
                }
                zip.closeEntry();
            }
        }
        if (!names.contains("[Content_Types].xml") || !names.contains("xl/workbook.xml")) {
            throw invalidUpload();
        }
        // POI chooses XML document parts by relationships, not by their filename.
        // Require every XML relationship target to declare XML and preflight it.
        for (Relationship relationship : relationships) {
            if (!relationship.binary && (!names.contains(relationship.target) || !types.isXml(relationship.target))) {
                throw invalidUpload();
            }
        }
        // Match POI's InputStream/local-header semantics on both passes. The first
        // pass reads Content_Types regardless of entry order; this pass covers XML
        // parts renamed to .bin/.dat while retaining an XML content type.
        expandedBytes[0] = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                EntryInputStream limited = new EntryInputStream(zip, expandedBytes);
                String name = entry.getName();
                if (types.isXml(name) && !name.endsWith(".xml") && !name.endsWith(".rels")) {
                    xmlLimits.entryName = name;
                    reader.parse(new InputSource(limited));
                }
                while (limited.read(buffer) != -1) { }
                zip.closeEntry();
            }
        }
    }

    private static final class ContentTypes {
        private final Map<String, String> defaults = new HashMap<String, String>();
        private final Map<String, String> overrides = new HashMap<String, String>();

        private boolean isXml(String name) {
            String type = overrides.get(name);
            int dot = name.lastIndexOf('.');
            if (type == null && dot >= 0) type = defaults.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (type == null) return false;
            type = type.toLowerCase(Locale.ROOT);
            return type.equals("application/xml") || type.equals("text/xml") || type.endsWith("+xml");
        }
    }

    private static final class Relationship {
        private final String target;
        private final boolean binary;

        private Relationship(String entryName, String type, String target) throws Exception {
            String base = entryName.equals("_rels/.rels") ? "/"
                    : "/" + entryName.substring(0, entryName.lastIndexOf("/_rels/") + 1);
            URI resolved = new URI(base).resolve(new URI(target)).normalize();
            if (resolved.isAbsolute() || resolved.getQuery() != null || resolved.getFragment() != null
                    || resolved.getPath() == null || !resolved.getPath().startsWith("/")) throw invalidUpload();
            this.target = resolved.getPath().substring(1);
            this.binary = type.endsWith("/image") || type.endsWith("/printerSettings")
                    || type.endsWith("/vbaProject") || type.endsWith("/oleObject") || type.endsWith("/package")
                    || type.endsWith("/metadata/thumbnail") || type.endsWith("/activeXControlBinary");
        }
    }

    private static final class EntryInputStream extends FilterInputStream {
        private final long[] total;
        private long count;

        private EntryInputStream(InputStream in, long[] total) {
            super(in);
            this.total = total;
        }

        private void count(int read) throws IOException {
            if (read > 0) {
                count += read;
                total[0] += read;
                if (count > MAX_ENTRY_BYTES || total[0] > MAX_EXPANDED_BYTES) throw invalidUpload();
            }
        }

        @Override public int read() throws IOException {
            int value = in.read();
            count(value < 0 ? 0 : 1);
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = in.read(bytes, offset, length);
            count(read);
            return read;
        }

        @Override public long skip(long length) throws IOException {
            long remaining = length;
            byte[] buffer = new byte[8192];
            while (remaining > 0) {
                int read = read(buffer, 0, (int) Math.min(remaining, buffer.length));
                if (read < 0) break;
                remaining -= read;
            }
            return length - remaining;
        }

        // SAX owns an entry stream, not the ZIP stream that contains later entries.
        @Override public void close() { }
    }

    private static final class XmlLimits extends DefaultHandler {
        private final ContentTypes types;
        private final List<Relationship> relationships;
        private String entryName;
        private int depth;
        private int elements;

        private XmlLimits(ContentTypes types, List<Relationship> relationships) {
            this.types = types;
            this.relationships = relationships;
        }

        @Override public void startDocument() { depth = 0; }

        @Override public void startElement(String uri, String local, String name, Attributes attributes) throws SAXException {
            if (++depth > 128 || ++elements > 500000 || attributes.getLength() > 128) {
                throw new SAXException("Excel XML exceeds the import limits.");
            }
            try {
                if ("[Content_Types].xml".equals(entryName)) {
                    String contentType = attributes.getValue("ContentType");
                    if ("Default".equals(local)) {
                        String extension = attributes.getValue("Extension");
                        if (extension == null || contentType == null
                                || types.defaults.put(extension.toLowerCase(Locale.ROOT), contentType) != null) throw invalidUpload();
                    } else if ("Override".equals(local)) {
                        String partName = attributes.getValue("PartName");
                        if (partName == null || !partName.startsWith("/") || contentType == null) throw invalidUpload();
                        String part = new URI(partName).getPath().substring(1);
                        if (types.overrides.put(part, contentType) != null) throw invalidUpload();
                    }
                    if (types.defaults.size() + types.overrides.size() > 512) throw invalidUpload();
                } else if (entryName.endsWith(".rels") && "Relationship".equals(local)
                        && !"External".equals(attributes.getValue("TargetMode"))) {
                    if (relationships.size() >= 2048 || attributes.getValue("Type") == null
                            || attributes.getValue("Target") == null) throw invalidUpload();
                    relationships.add(new Relationship(entryName, attributes.getValue("Type"), attributes.getValue("Target")));
                }
            } catch (Exception invalid) {
                throw new SAXException("Invalid Excel package metadata.", invalid);
            }
        }

        @Override public void endElement(String uri, String local, String name) { depth--; }
    }

    private static IOException invalidUpload() {
        return new IOException("Invalid Excel upload.");
    }
}
