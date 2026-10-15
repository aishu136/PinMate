package org.example.agent.bulk;

import org.example.agent.PinWriter;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Guesses a pin topic from a page URL when the feed gives no title, e.g. {@code /blog/easy-vegan-dinners/}. */
final class Topics {

    static final int MAX_TOPIC = 300;

    private Topics() {
    }

    static String forItem(String title, String url) {
        String t = title == null ? "" : title.strip().replaceAll("\\s+", " ");
        return PinWriter.truncate(t.isEmpty() ? fromUrl(url) : t, MAX_TOPIC);
    }

    static String fromUrl(String url) {
        URI uri = URI.create(url);
        String path = uri.getPath() == null ? "" : uri.getPath();
        List<String> segments = new ArrayList<>();
        for (String s : path.split("/")) {
            if (!s.isBlank()) {
                segments.add(s);
            }
        }
        Collections.reverse(segments);
        for (String segment : segments) {
            String words = segment.replaceFirst("\\.[A-Za-z0-9]{1,5}$", "")
                    .replaceAll("[-_+.]+", " ")
                    .strip();
            // Skip ids and dates such as /123/ or /2024/
            if (!words.isEmpty() && !words.matches("[0-9 ]+")) {
                return words;
            }
        }
        String host = uri.getHost() == null ? url : uri.getHost();
        return host.startsWith("www.") ? host.substring(4) : host;
    }
}
