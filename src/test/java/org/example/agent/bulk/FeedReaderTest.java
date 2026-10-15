package org.example.agent.bulk;

import org.example.agent.ApiException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedReaderTest {

    private static FeedReader.Parsed parse(String xml) {
        return FeedReader.parse(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsSitemaps() {
        FeedReader.Parsed p = parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc> https://example.com/vegan-dinners/ </loc><lastmod>2026-01-01</lastmod></url>
                  <url><loc>https://example.com/fall-decor</loc></url>
                </urlset>""");
        assertEquals(List.of(new FeedReader.Entry("https://example.com/vegan-dinners/", null),
                new FeedReader.Entry("https://example.com/fall-decor", null)), p.entries());
        assertTrue(p.childSitemaps().isEmpty());
    }

    @Test
    void readsSitemapIndexes() {
        FeedReader.Parsed p = parse("""
                <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <sitemap><loc>https://example.com/post-sitemap.xml</loc></sitemap>
                  <sitemap><loc>https://example.com/page-sitemap.xml</loc></sitemap>
                </sitemapindex>""");
        assertEquals(List.of("https://example.com/post-sitemap.xml", "https://example.com/page-sitemap.xml"),
                p.childSitemaps());
    }

    @Test
    void readsRssAndAtom() {
        FeedReader.Parsed rss = parse("""
                <rss version="2.0"><channel><title>Blog</title><link>https://example.com</link>
                  <item><title>Easy Vegan Dinners</title><link>https://example.com/vegan</link></item>
                  <item><title>No link</title></item>
                </channel></rss>""");
        assertEquals(List.of(new FeedReader.Entry("https://example.com/vegan", "Easy Vegan Dinners")), rss.entries());

        FeedReader.Parsed atom = parse("""
                <feed xmlns="http://www.w3.org/2005/Atom"><title>Blog</title>
                  <entry><title>Fall Decor</title>
                    <link rel="edit" href="https://example.com/api/1"/>
                    <link rel="alternate" href="https://example.com/fall-decor"/></entry>
                </feed>""");
        assertEquals(List.of(new FeedReader.Entry("https://example.com/fall-decor", "Fall Decor")), atom.entries());
    }

    @Test
    void rejectsNonFeedsAndEntityTricks() {
        ApiException html = assertThrows(ApiException.class, () -> parse("<html><body>Hi</body></html>"));
        assertEquals("not_a_feed", html.code());
        assertThrows(ApiException.class, () -> parse("not xml at all"));
        // External entities (XXE) and DTDs are refused outright.
        ApiException xxe = assertThrows(ApiException.class, () -> parse("""
                <?xml version="1.0"?>
                <!DOCTYPE urlset [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <urlset><url><loc>&secret;</loc></url></urlset>"""));
        assertEquals("not_a_feed", xxe.code());
    }

    @Test
    void unzipsGzippedSitemaps() throws IOException {
        byte[] xml = "<urlset><url><loc>https://example.com/a</loc></url></urlset>".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(xml);
        }
        assertArrayEquals(xml, FeedReader.gunzipIfNeeded(gz.toByteArray()));
        assertArrayEquals(xml, FeedReader.gunzipIfNeeded(xml));
    }

    @Test
    void blocksPrivateAndLocalAddresses() throws IOException {
        for (String ip : List.of("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254",
                "0.0.0.0", "100.64.0.1", "::1", "fe80::1", "fd00::1")) {
            assertFalse(FeedReader.isPublic(InetAddress.getByName(ip)), ip);
        }
        for (String ip : List.of("93.184.216.34", "8.8.8.8", "2606:4700::1111")) {
            assertTrue(FeedReader.isPublic(InetAddress.getByName(ip)), ip);
        }
        ApiException e = assertThrows(ApiException.class,
                () -> new FeedReader(false).checkAllowed(URI.create("http://127.0.0.1:8080/sitemap.xml")));
        assertEquals("blocked_host", e.code());
        assertThrows(ApiException.class, () -> new FeedReader(false).checkAllowed(URI.create("file:///etc/passwd")));
    }

    @Test
    void guessesTopicsFromUrls() {
        assertEquals("easy vegan dinners", Topics.fromUrl("https://example.com/blog/easy-vegan-dinners/"));
        assertEquals("fall living room", Topics.fromUrl("https://example.com/2024/09/fall_living_room.html"));
        assertEquals("recipes", Topics.fromUrl("https://example.com/recipes/12345"));
        assertEquals("example.com", Topics.fromUrl("https://www.example.com/"));
        assertEquals("Easy Vegan Dinners", Topics.forItem("  Easy   Vegan Dinners ", "https://example.com/x"));
        assertEquals("x", Topics.forItem(null, "https://example.com/x"));
    }
}
