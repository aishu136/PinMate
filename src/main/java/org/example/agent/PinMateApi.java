package org.example.agent;

import jakarta.ws.rs.core.Application;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeIn;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** OpenAPI metadata for {@code /q/openapi} and the Swagger UI at {@code /q/swagger-ui}. */
@OpenAPIDefinition(
        info = @Info(title = "PinMate API", version = "1.0.0",
                description = "Write Pinterest pin copy with Claude, publish it, or schedule it for later."),
        tags = {
                @Tag(name = "Pins", description = "Generate pin copy"),
                @Tag(name = "Pinterest", description = "Publish to the connected Pinterest account"),
                @Tag(name = "Schedule", description = "Publish pins at a later time"),
                @Tag(name = "Bulk", description = "Write pins for many pages from a sitemap, feed or URL list"),
                @Tag(name = "Brands", description = "Saved brand voices to generate with")
        },
        security = @SecurityRequirement(name = PinMateApi.API_KEY))
@SecurityScheme(securitySchemeName = PinMateApi.API_KEY, type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER, apiKeyName = ApiKeyFilter.HEADER,
        description = "Required when the server sets PINMATE_API_KEY; otherwise only localhost may call the API.")
public class PinMateApi extends Application {

    static final String API_KEY = "apiKey";
}
