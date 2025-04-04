/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.parser.csv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.input.CloseShieldInputStream;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.config.Field;
import org.apache.tika.detect.AutoDetectReader;
import org.apache.tika.detect.EncodingDetector;
import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Property;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AbstractEncodingDetectorParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.XHTMLContentHandler;

/**
 * Unless the {@link TikaCoreProperties#CONTENT_TYPE_USER_OVERRIDE} is set,
 * this parser tries to assess whether the file is a text file, csv or tsv.
 * If the detector detects regularity in column numbers and/or encapsulated cells,
 * this parser will apply the {@link org.apache.commons.csv.CSVParser};
 * otherwise, it will treat the contents as text.
 * <p>
 * If there is a csv parse exception during detection, the parser sets
 * the {@link Metadata#CONTENT_TYPE} to {@link MediaType#TEXT_PLAIN}
 * and treats the file as {@link MediaType#TEXT_PLAIN}.
 * </p>
 * <p>
 * If there is a csv parse exception during the parse, the parser
 * writes what's left of the stream as if it were text and then throws
 * an exception.  As of this writing, the content that was buffered by the underlying
 * {@link org.apache.commons.csv.CSVParser} is lost.
 * </p>
 */
public class TextAndCSVParser extends AbstractEncodingDetectorParser {

    static final MediaType CSV = MediaType.text("csv");
    static final MediaType TSV = MediaType.text("tsv");
    private static final String CSV_PREFIX = "csv";
    private static final String CHARSET = "charset";
    private static final String DELIMITER = "delimiter";
    public static final Property DELIMITER_PROPERTY = Property.externalText(
            CSV_PREFIX + TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER + DELIMITER);

    /**
     * If the file is detected as a csv/tsv, this is the number of columns in the first row.
     */
    public static final Property NUM_COLUMNS = Property.externalInteger(
            CSV_PREFIX + TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER + "num_columns");

    /**
     * If the file is detected as a csv/tsv, this is the number of rows if the file
     * is successfully read (e.g. no encapsulation exceptions, etc).
     */
    public static final Property NUM_ROWS = Property.externalInteger(
            CSV_PREFIX + TikaCoreProperties.NAMESPACE_PREFIX_DELIMITER + "num_rows");

    private static final String TD = "td";
    private static final String TR = "tr";
    private static final String TABLE = "table";
    private static final int DEFAULT_MARK_LIMIT = 20000;

    private static final Set<MediaType> SUPPORTED_TYPES = Collections
            .unmodifiableSet(new HashSet<>(Arrays.asList(CSV, TSV, MediaType.TEXT_PLAIN)));

    /**
     * This is the mark limit in characters (not bytes) to
     * read from the stream when classifying the stream as
     * csv, tsv or txt.
     */
    @Field
    private int markLimit = DEFAULT_MARK_LIMIT;


    /**
     * minimum confidence score that there's enough
     * evidence to determine csv/tsv vs. txt
     */
    @Field
    private double minConfidence = 0.50;

    public TextAndCSVParser() {
    }

    public TextAndCSVParser(EncodingDetector encodingDetector) {
        super(encodingDetector);
    }

    private static void handleText(Reader reader, XHTMLContentHandler xhtml)
            throws SAXException, IOException {
        xhtml.startElement("p");
        char[] buffer = new char[4096];
        int n = reader.read(buffer);
        while (n != -1) {
            xhtml.characters(buffer, 0, n);
            n = reader.read(buffer);
        }
        xhtml.endElement("p");

    }

    static boolean isCSVOrTSV(MediaType mediaType) {
        if (mediaType == null) {
            return false;
        }
        return mediaType.getBaseType().equals(TSV) || mediaType.getBaseType().equals(CSV);
    }

    private final TextAndCSVConfig defaultTextAndCSVConfig = new TextAndCSVConfig();
    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    @Override
    public void parse(InputStream stream, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException, TikaException {
        TextAndCSVConfig textAndCSVConfig = context.get(TextAndCSVConfig.class, defaultTextAndCSVConfig);

        CSVParams params = getOverride(metadata, textAndCSVConfig);
        Reader reader;
        Charset charset;
        if (!params.isComplete()) {
            reader = detect(params, textAndCSVConfig, stream, metadata, context);
            if (params.getCharset() != null) {
                charset = params.getCharset();
            } else {
                charset = ((AutoDetectReader) reader).getCharset();
            }
        } else {
            reader = new BufferedReader(new InputStreamReader(stream, params.getCharset()));
            charset = params.getCharset();
        }

        updateMetadata(params, metadata, textAndCSVConfig);

        //if text or a non-csv/tsv category of text
        //treat this as text and be done
        //TODO -- if it was detected as a non-csv subtype of text
        if (!params.getMediaType().getBaseType().equals(CSV) &&
                !params.getMediaType().getBaseType().equals(TSV)) {
            handleText(reader, charset, handler, metadata);
            return;
        }

        CSVFormat csvFormat = CSVFormat.EXCEL.builder().setDelimiter(params.getDelimiter()).get();
        metadata.set(DELIMITER_PROPERTY, textAndCSVConfig.getDelimiterToNameMap().get(csvFormat.getDelimiterString().charAt(0)));

        XHTMLContentHandler xhtmlContentHandler = new XHTMLContentHandler(handler, metadata);
        int totalRows = 0;
        try (CSVParser commonsParser = CSVParser.builder().setReader(reader).setFormat(csvFormat).get()) {
            xhtmlContentHandler.startDocument();
            xhtmlContentHandler.startElement(TABLE);
            int firstRowColCount = 0;
            try {
                for (CSVRecord row : commonsParser) {
                    xhtmlContentHandler.startElement(TR);
                    for (String cell : row) {
                        if (totalRows == 0) {
                            firstRowColCount++;
                        }
                        xhtmlContentHandler.startElement(TD);
                        xhtmlContentHandler.characters(cell);
                        xhtmlContentHandler.endElement(TD);
                    }
                    xhtmlContentHandler.endElement(TR);
                    if (totalRows == 0) {
                        metadata.set(NUM_COLUMNS, firstRowColCount);
                    }
                    totalRows++;
                }
                metadata.set(NUM_ROWS, totalRows);
            } catch (UncheckedIOException e) {
                if (e.getCause() != null && e.getCause().getMessage() != null &&
                        e.getCause().getMessage().contains("encapsulated")) {
                    //if there's a parse exception
                    //try to get the rest of the content...treat it as text for now
                    //There will be some content lost because of buffering.
                    //TODO -- figure out how to improve this
                    xhtmlContentHandler.endElement(TABLE);
                    xhtmlContentHandler.startElement("div", "name", "after exception");
                    handleText(reader, xhtmlContentHandler);
                    xhtmlContentHandler.endElement("div");
                    xhtmlContentHandler.endDocument();
                    //TODO -- consider dumping what's left in the reader as text
                    throw new TikaException("exception parsing the csv", e);
                } else {
                    if (e.getCause() != null) {
                        throw new TikaException("exception parsing the csv", e.getCause());
                    } else {
                        throw new TikaException("exception parsing the csv", e);
                    }
                }
            }

            xhtmlContentHandler.endElement(TABLE);
            xhtmlContentHandler.endDocument();
        }
    }

    private void handleText(Reader reader, Charset charset, ContentHandler handler,
                            Metadata metadata) throws SAXException, IOException, TikaException {
        // Automatically detect the character encoding
        //try to get detected content type; could be a subclass of text/plain
        //such as vcal, etc.
        String incomingMime = metadata.get(Metadata.CONTENT_TYPE);
        MediaType mediaType = MediaType.TEXT_PLAIN;
        if (incomingMime != null) {
            MediaType tmpMediaType = MediaType.parse(incomingMime);
            if (tmpMediaType != null) {
                mediaType = tmpMediaType;
            }
        }
        MediaType type = new MediaType(mediaType, charset);
        metadata.set(Metadata.CONTENT_TYPE, type.toString());
        // deprecated, see TIKA-431
        metadata.set(Metadata.CONTENT_ENCODING, charset.name());

        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata);
        xhtml.startDocument();
        handleText(reader, xhtml);
        xhtml.endDocument();
    }

    private Reader detect(CSVParams params, TextAndCSVConfig textAndCSVConfig, InputStream stream, Metadata metadata,
                          ParseContext context) throws IOException, TikaException {
        //if the file was already identified as not .txt, .csv or .tsv
        //don't even try to csv or not
        String mediaString = metadata.get(Metadata.CONTENT_TYPE);
        if (mediaString != null) {
            MediaType mediaType = MediaType.parse(mediaString);
            if (!SUPPORTED_TYPES.contains(mediaType.getBaseType())) {
                params.setMediaType(mediaType);
                return new AutoDetectReader(CloseShieldInputStream.wrap(stream), metadata,
                        getEncodingDetector(context));
            }
        }
        Reader reader;
        if (params.getCharset() == null) {
            reader = new AutoDetectReader(CloseShieldInputStream.wrap(stream), metadata,
                    getEncodingDetector(context));
            params.setCharset(((AutoDetectReader) reader).getCharset());
            if (params.isComplete()) {
                return reader;
            }
        } else {
            reader = new BufferedReader(
                    new InputStreamReader(CloseShieldInputStream.wrap(stream), params.getCharset()));
        }

        if (params.getDelimiter() == null &&
                (params.getMediaType() == null || isCSVOrTSV(params.getMediaType()))) {

            CSVSniffer sniffer = new CSVSniffer(markLimit, textAndCSVConfig.getDelimiterToNameMap().keySet(), minConfidence);
            CSVResult result = sniffer.getBest(reader, metadata);
            params.setMediaType(result.getMediaType());
            params.setDelimiter(result.getDelimiter());
        }
        return reader;
    }

    private CSVParams getOverride(Metadata metadata, TextAndCSVConfig textAndCSVConfig) {
        String override = metadata.get(TikaCoreProperties.CONTENT_TYPE_USER_OVERRIDE);
        if (override == null) {
            return new CSVParams();
        }
        MediaType mediaType = MediaType.parse(override);
        if (mediaType == null) {
            return new CSVParams();
        }
        String charsetString = mediaType.getParameters().get(CHARSET);
        Charset charset = null;
        if (charsetString != null) {
            try {
                charset = Charset.forName(charsetString);
            } catch (UnsupportedCharsetException e) {
                //swallow
            }
        }
        if (!isCSVOrTSV(mediaType)) {
            return new CSVParams(mediaType, charset);
        }

        String delimiterName = mediaType.getParameters().get(DELIMITER);
        if (delimiterName == null) {
            return new CSVParams(mediaType, charset);
        }
        if (textAndCSVConfig.getNameToDelimiterMap().containsKey(delimiterName)) {
            return new CSVParams(mediaType, charset,
                    (char) textAndCSVConfig.getNameToDelimiterMap().get(delimiterName));
        }
        if (delimiterName.length() == 1) {
            return new CSVParams(mediaType, charset, delimiterName.charAt(0));
        }
        //TODO: log bad/unrecognized delimiter string
        return new CSVParams(mediaType, charset);
    }

    private void updateMetadata(CSVParams params, Metadata metadata, TextAndCSVConfig textAndCSVConfig) {
        MediaType mediaType = null;
        if (params.getMediaType().getBaseType().equals(MediaType.TEXT_PLAIN)) {
            mediaType = MediaType.TEXT_PLAIN;
        } else if (params.getDelimiter() != null) {
            if (params.getDelimiter() == '\t') {
                mediaType = TSV;
            } else {
                mediaType = CSV;
            }
        } else {
            if (metadata.get(Metadata.CONTENT_TYPE) != null) {
                mediaType = MediaType.parse(metadata.get(Metadata.CONTENT_TYPE));
            }
        }
        Map<String, String> attrs = new HashMap<>();
        if (params.getCharset() != null) {
            attrs.put(CHARSET, params.getCharset().name());
            // deprecated, see TIKA-431
            metadata.set(Metadata.CONTENT_ENCODING, params.getCharset().name());
        }
        if (!MediaType.TEXT_PLAIN.equals(mediaType) && params.getDelimiter() != null) {
            if (textAndCSVConfig.getDelimiterToNameMap().containsKey(params.getDelimiter())) {
                attrs.put(DELIMITER, textAndCSVConfig.getDelimiterToNameMap().get(params.getDelimiter()));
            } else {
                attrs.put(DELIMITER, Integer.toString((int) params.getDelimiter()));
            }
        }
        MediaType type = new MediaType(mediaType, attrs);
        metadata.set(Metadata.CONTENT_TYPE, type.toString());
    }

    @Field
    public void setNameToDelimiterMap(Map<String, String> map) throws TikaConfigException {
        Map<String, Character> m = new HashMap<>();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getValue().length() > 1) {
                throw new TikaConfigException("delimiter must be a single character: " + e.getValue());
            }
            m.put(e.getKey(), e.getValue().charAt(0));
        }
        defaultTextAndCSVConfig.setNameToDelimiterMap(m);
    }

}
