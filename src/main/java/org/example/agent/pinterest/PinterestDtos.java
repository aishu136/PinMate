package org.example.agent.pinterest;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Pinterest API v5 wire types (snake_case), per the v5 OpenAPI spec. */
public final class PinterestDtos {

    private PinterestDtos() {
    }

    /** {@code PinCreate}: title ≤ 100, description ≤ 800, alt_text ≤ 500, link ≤ 2048. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PinCreate(
            @JsonProperty("board_id") String boardId,
            String title,
            String description,
            String link,
            @JsonProperty("alt_text") String altText,
            @JsonProperty("media_source") MediaSource mediaSource) {
    }

    /** {@code PinMediaSourceImageURL} or {@code PinMediaSourceImageBase64}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MediaSource(
            @JsonProperty("source_type") String sourceType,
            String url,
            @JsonProperty("content_type") String contentType,
            String data) {

        public static MediaSource imageUrl(String url) {
            return new MediaSource("image_url", url, null, null);
        }

        /** Pinterest accepts only image/jpeg and image/png here. */
        public static MediaSource imageBase64(String contentType, String base64) {
            return new MediaSource("image_base64", null, contentType, base64);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreatedPin(String id, @JsonProperty("board_id") String boardId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BoardPage(List<Board> items, String bookmark) {
    }

    /** Also returned by this app's API, so fields serialize as camelCase and deserialize from Pinterest's snake_case. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Board(String id, String name, String privacy, @JsonAlias("pin_count") Integer pinCount) {
    }

    /** {@code Pinterest.Lib.Error}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Error(Integer code, String message) {
    }
}
