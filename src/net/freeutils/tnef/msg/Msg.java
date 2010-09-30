/*
 *  (c) copyright 2003-2009 Amichai Rothman
 *
 *  This file is part of the Java TNEF package.
 *
 *  The Java TNEF package is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  The Java TNEF package is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package net.freeutils.tnef.msg;

import java.io.*;

import java.util.*;

import net.freeutils.tnef.*;

import org.apache.poi.poifs.filesystem.*;

/**
 * The <code>Msg</code> class allows access to ".msg" files, which
 * is the format used to save messages from Outlook.
 *
 * @author Amichai Rothman
 * @since 2007-06-16
 */
public class Msg {

    static final GUID PS_PUBLIC_STRINGS = new GUID("00020329-0000-0000-c000-000000000046");

    protected static class Properties {
        List props;
        int recipientCount;
        int attachmentCount;
        boolean isRootMessage;
    }

    public static void printDirectory(DirectoryEntry dir, String linePrefix) throws IOException {
        for (Iterator iter = dir.getEntries(); iter.hasNext(); ) {
            Entry entry = (Entry)iter.next();
            String name = entry.getName();
            if (entry instanceof DirectoryEntry) {
                DirectoryEntry de = (DirectoryEntry)entry;
                System.out.println(linePrefix + name + "/");
                printDirectory(de, linePrefix + "  ");
            } else if (entry instanceof DocumentEntry) {
                System.out.println(linePrefix + name + "  " + toRawInputStream((DocumentEntry)entry));
            } else {
                System.out.println(linePrefix + name + " (UNKNOWN entry type)");
            }
        }
    }

    public static Message processMessage(DirectoryEntry dir) throws IOException {
        return processMessage(dir, null);
    }

    protected static Message processMessage(DirectoryEntry dir, Map names) throws IOException {

        // process properties
        Properties properties = processProperties(dir, names);

        // translate temporary prop IDs to their names
        if (properties.isRootMessage) {
            names = processNameIDs(dir);
            translateNames(properties.props, names);
        }

        Message message = new Message();
        // add props
        MAPIProps mapiProps = new MAPIProps(
            (MAPIProp[])properties.props.toArray(new MAPIProp[properties.props.size()]));
        message.addAttribute(new Attr(Attr.LVL_MESSAGE, Attr.atpByte, Attr.attMAPIProps, mapiProps));
        // add recipients
        MAPIProps[] recipients = processRecipients(dir, properties.recipientCount, names);
        message.addAttribute(new Attr(Attr.LVL_MESSAGE, Attr.atpByte, Attr.attRecipTable, recipients));
        // add attachments
        List attachments = processAttachments(dir, properties.attachmentCount, names);
        message.setAttachments(attachments);

        return message;
    }

    protected static void translateNames(List props, Map names) {
        if (names != null) {
            for (Iterator it = props.iterator(); it.hasNext(); ) {
                MAPIProp prop = (MAPIProp)it.next();
                MAPIPropName name = (MAPIPropName)names.get(new Integer(prop.getID()));
                if (name != null)
                    prop.setName(name);
            }
        }
    }

    protected static MAPIProps[] processRecipients(DirectoryEntry dir, 
                    int recipientCount, Map names) throws IOException {
        Entry entry;
        MAPIProps[] recipients = new MAPIProps[recipientCount];
        for (int i = 0; i < recipientCount; i++) {
            String entryName = "__recip_version1.0_#" + toHexString(i);
            entry = dir.getEntry(entryName);
            Properties properties = processProperties((DirectoryEntry)entry, names);
            MAPIProp[] props = (MAPIProp[])properties.props.toArray(
                new MAPIProp[properties.props.size()]);
            recipients[i] = new MAPIProps(props);
        }
        return recipients;
    }


    protected static List processAttachments(DirectoryEntry dir, 
                    int attachmentCount, Map names) throws IOException {
        Entry entry;
        List attachments = new ArrayList();
        for (int i = 0; i < attachmentCount; i++) {
            String entryName = "__attach_version1.0_#" + toHexString(i);
            entry = dir.getEntry(entryName);
            Attachment attachment = processAttachment((DirectoryEntry)entry, names);
            attachments.add(attachment);
        }
        return attachments;
    }
    
    protected static Attachment processAttachment(DirectoryEntry dir, Map names) throws IOException {
        Properties properties = processProperties(dir, names);
        MAPIProp[] props = (MAPIProp[])properties.props.toArray(new MAPIProp[properties.props.size()]);
        Attachment att = new Attachment();
        att.setMAPIProps(new MAPIProps(props));
        // process nested object (if exists)
        try {
            DirectoryEntry entry = (DirectoryEntry)dir.getEntry("__substg1.0_3701000D");
            try {
                att.setNestedMessage(processMessage(entry, names));
            } catch (FileNotFoundException fnfe) {
                // it's not a nested message, but something else (e.g. signature image)
                att.setRawData(toRawInputStream((DocumentEntry)entry.getEntry("CONTENTS")));
            }
        } catch (FileNotFoundException fnfe) {
            // it's ok - just a regular attachment with no nested object
        }
        return att;
    }

    protected static Properties processProperties(DirectoryEntry dir, Map names) throws IOException {
        Properties properties = new Properties();
        Entry entry = dir.getEntry("__properties_version1.0");
        RawInputStream data = toRawInputStream((DocumentEntry)entry);
        // read header (all units are U32 words):
        // - in non-message attachments and recipients sections: {0, 0}
        // - in nested attached messages: {0, 0, n, m, n, m }
        //   where n is the number of recipients in the message,
        //   and m is the number of total attachments in the message
        // - in the root (top level) message: { 0, 0, n, m, n, m, 0, 0}
        //   where n and m are the same as above
        // heuristic: if upper 16 bits are zero, we're still in header
        int headerSize = 0;
        int maxHeaderSize = Math.min(data.available(), 32);
        RawInputStream header = (RawInputStream)data.newStream(0, maxHeaderSize);
        while (header.available() >= 4 && ((header.readU32() >> 16) & 0x0000FFFF) == 0)
            headerSize += 4;
        header.close();

        if (headerSize > 0) {
            if (headerSize < 8)
                throw new IOException("Unknown header format " + data.newStream(0, headerSize));
            if (data.readU32() != 0 || data.readU32() != 0)
                throw new IOException("Unknown header format " + data.newStream(0, headerSize));
        }
        if (headerSize > 8) {
            if (headerSize < 24)
                throw new IOException("Unknown header format " + data.newStream(0, headerSize));
            properties.recipientCount = (int)data.readU32();
            properties.attachmentCount = (int)data.readU32();
            if (data.readU32() != properties.recipientCount || data.readU32() != properties.attachmentCount)
                throw new IOException("Unknown header format " + data.newStream(0, headerSize));
        }
        if (headerSize > 24) {
            if (headerSize < 32)
                throw new IOException("Unknown header format " + data.newStream(0, headerSize));
            if (data.readU32() != 0 || data.readU32() != 0)
                throw new IOException("Unknown header format " + data.newStream(0, headerSize));
            properties.isRootMessage = true;
        }
        if (headerSize > 32)
            throw new IOException("Unknown header format " + data.newStream(0, headerSize));

        // read MAPI properties list
        properties.props = processPropertyList(data);
        data.close();

        // add all standalone property entries
        properties.props.addAll(processPropEntries(dir));

        // translate named properties
        translateNames(properties.props, names);

        return properties;
    }

    protected static List processPropertyList(RawInputStream data) throws IOException {
        // prepare a list of properties,
        // each made up of 4 U32 words:
        // the first is the MAPI id and type,
        // the second is unknown (observed values are 2, 6, 7)
        // and the 3rd and 4th, according to the value type's length:
        //   4: the value and an unknown pad value
        //   8: the value
        //  >8 and variable length: the length and an unknown pad value
        //      (the value itself will be in a separate
        //       document entry with this property ID)
        List props = new ArrayList();
        while (data.available() > 0) {
            int type = data.readU16();
            type = type & ~MAPIProp.MV_FLAG; // remove MV_FLAG
            int id = data.readU16();
            data.readU32(); // something1
            MAPIValue val = null;
            switch (type) {
                case MAPIProp.PT_NULL:
                    break;

                case MAPIProp.PT_INT:
                case MAPIProp.PT_FLOAT:
                case MAPIProp.PT_ERROR:
                case MAPIProp.PT_BOOLEAN:   // 2 bytes + padding
                case MAPIProp.PT_SHORT:     // 2 bytes + padding
                    // 4 bytes
                    val = new MAPIValue(type, data, 4);
                    data.readU32(); // something2
                    break;

                case MAPIProp.PT_DOUBLE:
                case MAPIProp.PT_APPTIME:
                case MAPIProp.PT_CURRENCY:
                case MAPIProp.PT_INT8BYTE:
                case MAPIProp.PT_SYSTIME:
                    // 8 bytes
                    val = new MAPIValue(type, data, 8);
                    break;

                case MAPIProp.PT_CLSID:
                case MAPIProp.PT_STRING:
                case MAPIProp.PT_UNICODE_STRING:
                case MAPIProp.PT_OBJECT:
                case MAPIProp.PT_BINARY:
                    // get value length
                    // (the value itself will be in a separate document entry)
                    int vlen = (int)data.readU32();
                    data.readU32(); // something2
                    break;
                default:
                    throw new IOException("Unknown MAPI type: 0x" + Integer.toHexString(type));
            }

            if (val != null)
                props.add(new MAPIProp(type, id, new MAPIValue[] { val }));

        }
        return props;
    }

    protected static Map processNameIDs(DirectoryEntry dir) throws IOException {
        DirectoryEntry entry = (DirectoryEntry)dir.getEntry("__nameid_version1.0");
        Map names = new HashMap();
        // parse guids
        MAPIProp guidsProp = processProperty((DocumentEntry)entry.getEntry("__substg1.0_00020102"));
        RawInputStream guidsData = (RawInputStream)guidsProp.getValue();
        List guids = new ArrayList();
        // add const guids
        guids.add(PS_PUBLIC_STRINGS);
        guids.add(PS_PUBLIC_STRINGS); // todo: find out which one goes here
        guids.add(PS_PUBLIC_STRINGS); // todo: find out which one goes here
        while (guidsData.available() > 0)
            guids.add(new GUID(guidsData.readBytes(16)));

        MAPIProp namesProp = processProperty((DocumentEntry)entry.getEntry("__substg1.0_00040102"));
        byte[] namesData = ((RawInputStream)namesProp.getValue()).toByteArray();
        
        MAPIProp propsProp = processProperty((DocumentEntry)entry.getEntry("__substg1.0_00030102"));
        RawInputStream propsData = (RawInputStream)propsProp.getValue();
        while (propsData.available() > 0) {
            byte[] b = propsData.readBytes(8);
            int flags = TNEFUtils.getU16(b, 4);
            int num = TNEFUtils.getU16(b, 6);
            int tempid = 0x8000 + num;
            GUID guid = (GUID)guids.get(flags >> 1);
            MAPIPropName propName;
            if ((flags & 1) != 0) {
                // has name
                int offset = (int)TNEFUtils.getU32(b, 0);
                int len = (int)TNEFUtils.getU32(namesData, offset);
                String nameStr = TNEFUtils.createStringUnicode(namesData, offset + 4, len);
                propName = new MAPIPropName(guid, nameStr);
            } else {
                // has id
                int id = TNEFUtils.getU16(b, 0);
                propName = new MAPIPropName(guid, id);
            }
            names.put(new Integer(tempid), propName);
        }
        return names;
    }

    protected static List processPropEntries(DirectoryEntry dir) throws IOException {
        List props = new ArrayList();
        for (Iterator iter = dir.getEntries(); iter.hasNext(); ) {
            Entry entry = (Entry)iter.next();
            if (entry instanceof DocumentEntry && entry.getName().startsWith("__substg1.0_"))
                props.add(processProperty((DocumentEntry)entry));
        }
        return props;
    }

    protected static MAPIProp processProperty(DocumentEntry entry) throws IOException {
        String name = entry.getName();
        int id = Integer.parseInt(name.substring(12, 16), 16);
        int type = Integer.parseInt(name.substring(16, 20), 16);
        type = type & ~MAPIProp.MV_FLAG; // remove MV_FLAG
        RawInputStream data = toRawInputStream(entry);
        MAPIValue val = new MAPIValue(type, data, (int)data.getLength());
        MAPIProp prop = new MAPIProp(type, id, new MAPIValue[] { val });
        data.close();
        return prop;
    }

    protected static RawInputStream toRawInputStream(DocumentEntry entry) throws IOException {
        DocumentInputStream dis = new DocumentInputStream(entry);
        ByteArrayOutputStream bais = new ByteArrayOutputStream(dis.available());
        try {
            byte[] bytes = new byte[4096];
            int count;
            while ((count = dis.read(bytes)) > -1)
                bais.write(bytes, 0, count);
        } finally {
            dis.close();
        }
        return new RawInputStream(bais.toByteArray());
    }

    protected static String toHexString(int i) {
        String s = Integer.toHexString(i).toUpperCase();
        while (s.length() < 8)
            s = "0" + s;
        return s;
    }

    public static void main(String[] args) throws Exception {
        String filename = args[0];
        String outputdir = args[1];
        InputStream in = null;
        try {
            in = new FileInputStream(filename);
            POIFSFileSystem fs = new POIFSFileSystem(in);
            DirectoryEntry root = fs.getRoot();
            //printDirectory(root, "");
            Message message = processMessage(root);
            TNEF.extractContent(message, outputdir);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        } finally {
            if (in != null)
                in.close();
        }
    }

}
