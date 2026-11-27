package com.hub.backend.common.config;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serves the one springdoc endpoint Knife4j needs, so that Swagger UI does not have to ship.
 *
 * <p>Knife4j's front end reads {@code /v3/api-docs/swagger-config} to find out which document to
 * load. The request is built inside its bundle, so it cannot be pointed elsewhere. That handler
 * normally comes from the swagger-ui autoconfiguration
 * ({@code springdoc-openapi-starter-webmvc-ui}) — which also drags in the Swagger UI console, and
 * switching the console off with {@code springdoc.swagger-ui.enabled=false} removes the handler
 * with it.</p>
 *
 * <p>The failure mode is worth spelling out, because it is silent: {@code /doc.html} keeps
 * answering {@code 200} — it is only a static page — while every request it makes behind the scenes
 * 404s. The console opens blank, which reads as "the doc site is down" rather than as a
 * misconfiguration. That is exactly what happened when the artifact was first swapped, so the
 * endpoint is now produced here and the whole chain is covered by
 * {@code DocumentationConsoleTest}.</p>
 *
 * <p>The payload matches what springdoc returns (verified against it): {@code configUrl},
 * {@code url}, {@code validatorUrl}. {@code oauth2RedirectUrl} is omitted on purpose — Knife4j never
 * reads it (its bundle contains no reference), and it would point at a Swagger UI page that no
 * longer exists.</p>
 *
 * <p>Hidden from the published document: this is console plumbing, not part of the API contract.</p>
 */
@RestController
@Hidden
public class SwaggerConfigEndpoint {

    private final String apiDocsPath;

    public SwaggerConfigEndpoint(@Value("${springdoc.api-docs.path:/v3/api-docs}") String apiDocsPath) {
        this.apiDocsPath = apiDocsPath;
    }

    @GetMapping("${springdoc.api-docs.path:/v3/api-docs}/swagger-config")
    public Map<String, Object> swaggerConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("configUrl", apiDocsPath + "/swagger-config");
        config.put("url", apiDocsPath);
        config.put("validatorUrl", "");
        return config;
    }
}
