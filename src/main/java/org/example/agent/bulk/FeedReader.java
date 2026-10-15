package org.example.agent.bulk;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.agent.ApiException;
import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Downloads a sitemap, sitemap index, RSS or Atom feed and lists the page URLs in it. */
@ApplicationScoped
public class FeedReader {

    static final int MAX_BYTES = 10 * 1024 * 1024;
    static final int MAX_URLS = 200;
    static final int MAX_CHILD_SITEMAPS = 5;
    static final int MAX_REDIRECTS = 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static final Logger LOG = Logger.getLogger(FeedReader.class);

    /** A page found in a feed; {@code title} is null when the feed doesn't give one (sitemaps). */
    public record Entry(String url, String title) {
    }

    /** Either page entries, or child sitemap URLs for a sitemap index. */
    record Parsed(List<Entry> entries, List<String> childSitemaps) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER) // followed by hand so every hop is checked
            .connectTimeout(TIMEOUT)
            .build();
    private final boolean allowPrivateHosts;

    public FeedReader(@ConfigProperty(name = "pinmate.bulk.allow-private-hosts", defaultValue = "false")
                      boolean allowPrivateHosts) {
        this.allowPrivateHosts = allowPrivateHosts;
    }

    /** Page URLs in the feed at {@code source}, de-duplicated, at most {@link #MAX_URLS}. */
    public List<Entry> discover(String source) {
        URI uri = parseUri(source);
        Parsed parsed = parse(fetch(uri));
        Map<String, Entry> found = new LinkedHashMap<>();
        addAll(found, parsed.entries());
        int children = 0;
        for (String child : parsed.childSitemaps()) {
            if (found.size() >= MAX_URLS || children++ >= MAX_CHILD_SITEMAPS) {
                break;
            }
            try {
                addAll(found, parse(fetch(uri.resolve(child.strip()))).entries());
            } catch (ApiException | IllegalArgumentException e) {
                LOG.infof("Skipping child sitemap %s: %s", child, e.getMessage());
            }
        }
        return List.copyOf(found.values());
    }

    private static void addAll(Map<String, Entry> found, List<Entry> entries) {
        for (Entry e : entries) {
            if (found.size() >= MAX_URLS) {
                return;
            }
            if (isWebUrl(e.url())) {
                found.putIfAbsent(e.url(), e);
            }
        }
    }

    static boolean isWebUrl(String url) {
        try {
            URI u = new URI(url);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && u.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static URI parseUri(String source) {
        String s = source == null ? "" : source.strip();
        if (!isWebUrl(s)) {
            throw new ApiException(400, "invalid_source", "Enter an http:// or https:// URL of a sitemap or feed.");
        }
        return URI.create(s);
    }

    // --- download ---

    byte[] fetch(URI uri) {
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkAllowed(uri);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("User-Agent", "PinMate/1.0 (pin generator)")
                    .header("Accept", "application/xml, text/xml, application/rss+xml, application/atom+xml, */*;q=0.5")
                    .GET().build();
            HttpResponse<InputStream> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw unreachable(uri, "it could not be reached");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw unreachable(uri, "the download was interrupted");
            }
            int status = response.statusCode();
            URI current = uri;
            try (InputStream body = response.body()) {
                if (status >= 300 && status < 400 && status != 304) {
                    String location = response.headers().firstValue("Location")
                            .orElseThrow(() -> unreachable(current, "it redirected without a location"));
                    uri = current.resolve(location.strip());
                    continue;
                }
                if (status != 200) {
                    throw unreachable(uri, "the server returned HTTP " + status);
                }
                return gunzipIfNeeded(readCapped(body));
            } catch (IOException e) {
                throw unreachable(uri, "the download failed");
            }
        }
        throw unreachable(uri, "it redirected too many times");
    }

    private static ApiException unreachable(URI uri, String why) {
        return new ApiException(422, "feed_unreachable", "Couldn't download " + uri + ": " + why + ".");
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        byte[] data = in.readNBytes(MAX_BYTES + 1);
        if (data.length > MAX_BYTES) {
            throw new ApiException(422, "feed_too_large", "The feed is larger than 10 MB.");
        }
        return data;
    }

    /** Sitemaps are often served as .xml.gz. */
    static byte[] gunzipIfNeeded(byte[] data) throws IOException {
        if (data.length < 2 || (data[0] & 0xFF) != 0x1F || (data[1] & 0xFF) != 0x8B) {
            return data;
        }
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return readCapped(in);
        }
    }

    /** Refuses hosts on this machine or the private network, so the server can't be used to probe them. */
    void checkAllowed(URI uri) {
        if (!isWebUrl(uri.toString())) {
            throw new ApiException(422, "feed_unreachable", "Only http:// and https:// URLs can be downloaded.");
        }
        if (allowPrivateHosts) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw unreachable(uri, "the host name doesn't resolve");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new ApiException(400, "blocked_host",
                        "Feeds on private or local network addresses can't be downloaded.");
            }
        }
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            return first != 0 && !(first == 100 && second >= 64 && second <= 127); // 0.0.0.0/8, CGNAT 100.64/10
        }
        if (a instanceof Inet6Address) {
            return (b[0] & 0xFE) != 0xFC; // unique local fc00::/7
        }
        return true;
    }

    // --- parse ---

    static Parsed parse(byte[] xml) {
        Document doc;
        try {
            doc = newBuilder().parse(new ByteArrayInputStream(xml));
        } catch (SAXException | IOException e) {
            throw notAFeed();
        }
        Element root = doc.getDocumentElement();
        List<Entry> entries = new ArrayList<>();
        List<String> children = new ArrayList<>();
        switch (localName(root)) {
            case "urlset" -> {
                for (Element url : children(root, "url")) {
                    String loc = childText(url, "loc");
                    if (loc != null) {
                        entries.add(new Entry(loc, null));
                    }
                }
            }
            case "sitemapindex" -> {
                for (Element sitemap : children(root, "sitemap")) {
                    String loc = childText(sitemap, "loc");
                    if (loc != null) {
                        children.add(loc);
                    }
                }
            }
            case "rss", "RDF" -> {
                NodeList items = root.getElementsByTagNameNS("*", "item");
                for (int i = 0; i < items.getLength(); i++) {
                    Element item = (Element) items.item(i);
                    String link = childText(item, "link");
                    if (link != null) {
                        entries.add(new Entry(link, childText(item, "title")));
                    }
                }
            }
            case "feed" -> {
                for (Element entry : children(root, "entry")) {
                    String link = atomLink(entry);
                    if (link != null) {
                        entries.add(new Entry(link, childText(entry, "title")));
                    }
                }
            }
            default -> throw notAFeed();
        }
        return new Parsed(entries, children);
    }

    private static ApiException notAFeed() {
        return new ApiException(422, "not_a_feed", "That URL isn't a sitemap, RSS feed or Atom feed.");
    }

    /** XML parser with DTDs and external entities disabled (no XXE or entity expansion attacks). */
    private static DocumentBuilder newBuilder() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder builder = f.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() { // throw instead of printing to stderr
                @Override
                public void warning(SAXParseException e) {
                }

                @Override
                public void error(SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            return builder;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML parser not available", e);
        }
    }

    private static String localName(Node n) {
        return n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(localName(e))) {
                out.add(e);
            }
        }
        return out;
    }

    private static String childText(Element parent, String name) {
        for (Element e : children(parent, name)) {
            String text = e.getTextContent() == null ? "" : e.getTextContent().strip();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    /** The entry's page link: {@code rel="alternate"} or no rel. */
    private static String atomLink(Element entry) {
        for (Element link : children(entry, "link")) {
            String rel = link.getAttribute("rel");
            String href = link.getAttribute("href").strip();
            if ((rel.isEmpty() || rel.equals("alternate")) && !href.isEmpty()) {
                return href;
            }
        }
        return null;
    }
}
