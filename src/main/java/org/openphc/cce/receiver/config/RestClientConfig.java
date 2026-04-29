package org.openphc.cce.receiver.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Configuration
@EnableConfigurationProperties(OpenMrsProperties.class)
public class RestClientConfig {

    private final OpenMrsProperties properties;
    private final OAuth2TokenProvider tokenProvider;

    public RestClientConfig(OpenMrsProperties properties, OAuth2TokenProvider tokenProvider) {
        this.properties = properties;
        this.tokenProvider = tokenProvider;
    }

    @Bean
    @Qualifier("openmrsRestClient")
    public RestClient openmrsRestClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.rest().timeout());
        factory.setReadTimeout(properties.rest().timeout());

        var builder = RestClient.builder()
                .baseUrl(properties.rest().baseUrl())
                .requestFactory(factory)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE);

        addAuthHeader(builder);
        return builder.build();
    }

    @Bean
    @Qualifier("fhirRestClient")
    public RestClient fhirRestClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.fhir().timeout());
        factory.setReadTimeout(properties.fhir().timeout());

        var builder = RestClient.builder()
                .baseUrl(properties.fhir().baseUrl())
                .requestFactory(factory)
                .defaultHeader("Content-Type", "application/fhir+json")
                .defaultHeader("Accept", "application/fhir+json");

        addAuthHeader(builder);
        return builder.build();
    }

    private void addAuthHeader(RestClient.Builder builder) {
        if (properties.auth() == null) return;

        if ("oauth2".equalsIgnoreCase(properties.auth().type())) {
            builder.requestInterceptor((request, body, execution) -> {
                String token = tokenProvider.getAccessToken();
                request.getHeaders().setBearerAuth(token);
                return execution.execute(request, body);
            });
        } else {
            String credentials = properties.auth().username() + ":" + properties.auth().password();
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            builder.defaultHeader("Authorization", "Basic " + encoded);
        }
    }
}
