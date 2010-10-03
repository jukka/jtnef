/*
 *  (c) copyright 2010 Jukka Zitting
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
package net.freeutils.tnef.tika;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Set;

import net.freeutils.tnef.CompressedRTFInputStream;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.DelegatingParser;
import org.apache.tika.parser.ParseContext;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/**
 * Minimal Tika parser for TNEF-compressed documents.
 */
public class TNEFParser extends DelegatingParser {

    public Set getSupportedTypes(ParseContext context) {
        return Collections.singleton(MediaType.application("vnd.ms-tnef"));
    }

    public void parse(
            InputStream stream, ContentHandler handler,
            Metadata metadata, ParseContext context)
            throws IOException, SAXException, TikaException {
        super.parse(
                new CompressedRTFInputStream(stream),
                handler, metadata, context);
    }

}
