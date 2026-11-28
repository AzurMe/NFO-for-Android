package org.kodiscraper.app;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

final class KodiNfoPreservation {
    private static final int MAX_ELEMENT_DEPTH = 64;
    private static final Pattern MOVIE_END = Pattern.compile("</movie\\s*>");
    private static final Set<String> PERSONAL_FIELDS = words(
            "sorttitle userrating playcount lastplayed resume dateadded");
    private static final Set<String> REPLACED_FIELDS = words(
            "title originaltitle originallanguage ratings rating votes top250 outline plot tagline runtime"
                    + " thumb fanart art mpaa id uniqueid genre set country credits director premiered year"
                    + " status code aired studio trailer fileinfo actor file path filenameandpath basepath"
                    + " original_filename originalfilename episodeguide imdbid tmdbid tvdbid wikidataid"
                    + " playlist hasvideoversions hasvideoextras isdefaultvideoversion");

    private KodiNfoPreservation() {}

    // Existing sidecars are ordered highest priority first.
    static byte[] merge(byte[] generated, byte[][] existing) throws IOException {
        if (existing == null || existing.length == 0) {
            return generated;
        }
        Fields target = null;
        for (byte[] previous : existing) {
            if (previous == null || previous.length == 0) {
                continue;
            }
            String payload = decode(previous);
            if (payload.trim().isEmpty() || isLegacyUrlList(payload)) {
                continue;
            }
            Document source = parseMovie(combinationXml(payload));
            if (target == null) {
                target = new Fields(parseMovie(decode(generated)));
            }
            target.merge(source.getDocumentElement());
        }
        return target == null || !target.changed ? generated : serialize(target.document);
    }

    private static final class Fields {
        final Document document;
        final Element movie;
        final Set<String> singletons = new HashSet<>();
        final Set<String> tags = new HashSet<>();
        final Set<String> showLinks = new HashSet<>();
        final Set<String> ratingProviders = new HashSet<>();
        final List<Element> custom = new ArrayList<>();
        Element ratings;
        boolean defaultRating;
        boolean changed;

        Fields(Document document) {
            this.document = document;
            this.movie = document.getDocumentElement();
            for (Element child : children(movie)) {
                String name = child.getTagName();
                switch (name) {
                    case "ratings":
                        ratings = child;
                        for (Element rating : children(child)) {
                            if ("rating".equals(rating.getTagName())) {
                                ratingProviders.add(provider(rating));
                                defaultRating |= isDefault(rating);
                            }
                        }
                        break;
                    case "tag":
                        tags.add(listValue(child));
                        break;
                    case "showlink":
                        showLinks.add(listValue(child));
                        break;
                    default:
                        if (PERSONAL_FIELDS.contains(name)) {
                            singletons.add(name);
                        } else if (!REPLACED_FIELDS.contains(name)) {
                            custom.add(child);
                        }
                        break;
                }
            }
        }

        void merge(Element source) {
            for (Element child : children(source)) {
                String name = child.getTagName();
                switch (name) {
                    case "ratings":
                        mergeRatings(child);
                        break;
                    case "tag":
                        appendListItem(child, tags);
                        break;
                    case "showlink":
                        appendListItem(child, showLinks);
                        break;
                    default:
                        if (PERSONAL_FIELDS.contains(name)) {
                            if (singletons.add(name)) {
                                append(child);
                            }
                        } else if (!REPLACED_FIELDS.contains(name) && !containsCustom(child)) {
                            custom.add(append(child));
                        }
                        break;
                }
            }
        }

        private void appendListItem(Element child, Set<String> alreadyAdded) {
            String value = listValue(child);
            if (!value.isEmpty() && alreadyAdded.add(value)) {
                append(child).removeAttribute("clear");
            }
        }

        private boolean containsCustom(Element child) {
            for (Element previous : custom) {
                if (previous.isEqualNode(child)) {
                    return true;
                }
            }
            return false;
        }

        private Element append(Element child) {
            Element imported = (Element) document.importNode(child, true);
            movie.appendChild(imported);
            changed = true;
            return imported;
        }

        private void mergeRatings(Element source) {
            for (Element rating : children(source)) {
                String provider = provider(rating);
                if (!"rating".equals(rating.getTagName()) || provider.isEmpty()
                        || "default".equals(provider) || "themoviedb".equals(provider)
                        || "tmdb".equals(provider) || !ratingProviders.add(provider)) {
                    continue;
                }
                if (ratings == null) {
                    ratings = document.createElement("ratings");
                    movie.appendChild(ratings);
                }
                Element copy = (Element) document.importNode(rating, true);
                boolean keepDefault = !defaultRating && isDefault(rating);
                copy.setAttribute("default", Boolean.toString(keepDefault));
                defaultRating |= keepDefault;
                ratings.appendChild(copy);
                changed = true;
            }
        }
    }

    static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) child);
            }
        }
        return result;
    }

    private static String listValue(Element child) {
        return child.getTextContent().trim().toLowerCase(Locale.ROOT);
    }

    private static String provider(Element rating) {
        return rating.getAttribute("name").trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isDefault(Element rating) {
        return "true".equalsIgnoreCase(rating.getAttribute("default"));
    }

    private static Set<String> words(String values) {
        return new HashSet<>(Arrays.asList(values.split(" ")));
    }

    private static String decode(byte[] bytes) throws IOException {
        Charset charset = StandardCharsets.UTF_8;
        int offset = 0;
        if (startsWith(bytes, 0xef, 0xbb, 0xbf)) {
            offset = 3;
        } else if (startsWith(bytes, 0xfe, 0xff)) {
            charset = StandardCharsets.UTF_16BE;
            offset = 2;
        } else if (startsWith(bytes, 0xff, 0xfe)) {
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
        } else if (startsWith(bytes, 0, '<')) {
            charset = StandardCharsets.UTF_16BE;
        } else if (startsWith(bytes, '<', 0)) {
            charset = StandardCharsets.UTF_16LE;
        }
        try {
            String value = charset.newDecoder().decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
            if (value.indexOf(0) >= 0) {
                throw new IOException("现有 NFO 含无效字符，已停止覆盖");
            }
            return value;
        } catch (CharacterCodingException invalidEncoding) {
            throw new IOException("现有 NFO 编码无效，已停止覆盖", invalidEncoding);
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if ((bytes[index] & 0xff) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static String combinationXml(String payload) {
        Matcher closing = MOVIE_END.matcher(payload);
        int end = -1;
        while (closing.find()) {
            end = closing.end();
        }
        return end >= 0 && isLegacyUrlList(payload.substring(end)) ? payload.substring(0, end) : payload;
    }

    private static boolean isLegacyUrlList(String payload) {
        String stripped = payload.trim();
        if (!stripped.startsWith("http://") && !stripped.startsWith("https://")) {
            return false;
        }
        for (String value : stripped.split("\\s+")) {
            try {
                URI uri = new URI(value);
                String scheme = uri.getScheme();
                if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme)
                        || "https".equalsIgnoreCase(scheme))) {
                    return false;
                }
            } catch (URISyntaxException malformed) {
                return false;
            }
        }
        return true;
    }

    static Element readMovie(byte[] nfo) throws IOException {
        return parseMovie(combinationXml(decode(nfo))).getDocumentElement();
    }

    private static Document parseMovie(String xml) throws IOException {
        rejectDeclarations(xml);
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder parser = factory.newDocumentBuilder();
            parser.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("NFO 不允许使用外部实体");
            });
            parser.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException invalid) throws SAXException {
                    throw invalid;
                }

                @Override
                public void fatalError(SAXParseException invalid) throws SAXException {
                    throw invalid;
                }
            });
            Document parsed = parser.parse(new InputSource(new StringReader(xml)));
            if (!"movie".equals(parsed.getDocumentElement().getTagName())) {
                throw new IOException("现有 NFO 根节点不是 movie，已停止覆盖");
            }
            return parsed;
        } catch (ParserConfigurationException | SAXException malformed) {
            throw new IOException("现有 NFO 结构无效，已停止覆盖", malformed);
        }
    }

    // The UTF input is already decoded. Ignore literal text inside comments, CDATA and processing
    // instructions, while rejecting declarations before the platform DOM parser can process them.
    private static void rejectDeclarations(String xml) throws IOException {
        int depth = 0;
        for (int start = xml.indexOf('<'); start >= 0; start = xml.indexOf('<', start + 1)) {
            String endSequence;
            if (xml.startsWith("<!DOCTYPE", start) || xml.startsWith("<!ENTITY", start)) {
                throw new IOException("现有 NFO 包含 DTD 或实体声明，已停止覆盖");
            } else if (xml.startsWith("<!--", start)) {
                endSequence = "-->";
            } else if (xml.startsWith("<![CDATA[", start)) {
                endSequence = "]]>";
            } else if (xml.startsWith("<?", start)) {
                endSequence = "?>";
            } else {
                if (xml.startsWith("</", start)) {
                    depth--;
                } else {
                    int end = tagEnd(xml, start);
                    if (end > start && xml.charAt(end - 1) != '/' && ++depth > MAX_ELEMENT_DEPTH) {
                        throw new IOException("现有 NFO 嵌套过深，已停止覆盖");
                    }
                }
                continue;
            }
            int end = xml.indexOf(endSequence, start + 2);
            if (end < 0) {
                throw new IOException("现有 NFO 结构无效，已停止覆盖");
            }
            start = end + endSequence.length() - 1;
        }
    }

    private static int tagEnd(String xml, int start) throws IOException {
        char quote = 0;
        for (int index = start + 1; index < xml.length(); index++) {
            char character = xml.charAt(index);
            if (character == '<') {
                break;
            }
            if (quote != 0) {
                if (character == quote) {
                    quote = 0;
                }
            } else if (character == '>') {
                return index;
            } else if (character == '"' || character == '\'') {
                quote = character;
            }
        }
        throw new IOException("现有 NFO 结构无效，已停止覆盖");
    }

    private static byte[] serialize(Document xml) throws IOException {
        try {
            Transformer serializer = TransformerFactory.newInstance().newTransformer();
            serializer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            serializer.setOutputProperty(OutputKeys.INDENT, "yes");
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            serializer.transform(new DOMSource(xml), new StreamResult(result));
            return result.toByteArray();
        } catch (TransformerException failure) {
            throw new IOException("无法保留现有 NFO 的自定义字段，已停止覆盖", failure);
        }
    }
}
