package org.example.agent.bulk;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/** Feeds served by the test app for {@link FeedReader} to download. */
@Path("/fake-feeds")
public class FakeFeeds {

    @GET
    @Path("/sitemap-index.xml")
    @Produces("application/xml")
    public String sitemapIndex() {
        // Relative locations resolve against the index URL, so the tests don't need the port.
        return """
                <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <sitemap><loc>posts.xml</loc></sitemap>
                  <sitemap><loc>missing.xml</loc></sitemap>
                </sitemapindex>""";
    }

    @GET
    @Path("/posts.xml")
    @Produces("application/xml")
    public String posts() {
        return """
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>https://blog.example.com/easy-vegan-dinners/</loc></url>
                  <url><loc>https://blog.example.com/fall-living-room</loc></url>
                  <url><loc>https://blog.example.com/fall-living-room</loc></url>
                  <url><loc>ftp://blog.example.com/not-a-page</loc></url>
                </urlset>""";
    }

    @GET
    @Path("/moved")
    public Response moved(@Context UriInfo uri) {
        return Response.status(301).location(uri.getRequestUri().resolve("rss.xml")).build();
    }

    @GET
    @Path("/rss.xml")
    @Produces("application/rss+xml")
    public String rss() {
        return """
                <rss version="2.0"><channel><title>Blog</title>
                  <item><title>Cozy Fall Living Room Ideas</title><link>https://blog.example.com/cozy-fall</link></item>
                </channel></rss>""";
    }

    @GET
    @Path("/page.html")
    @Produces("text/html")
    public String page() {
        return "<!doctype html><html><body>Not a feed</body></html>";
    }
}
