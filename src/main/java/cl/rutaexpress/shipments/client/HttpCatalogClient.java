package cl.rutaexpress.shipments.client;

import cl.rutaexpress.shipments.exception.CapacityUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpCatalogClient implements CatalogClient {
    private final RestClient restClient;
    private final String capacityPath;

    public HttpCatalogClient(RestClient.Builder builder,
                             @Value("${rutaexpress.catalog.base-url}") String baseUrl,
                             @Value("${rutaexpress.catalog.capacity-path}") String capacityPath) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.capacityPath = capacityPath;
    }

    @Override
    public void decrementCapacity(Long serviceId, String shipmentTrackingNumber) {
        var request = restClient.post()
                .uri(uriBuilder -> uriBuilder.path(capacityPath).build(serviceId))
                .body(new CapacityRequest(shipmentTrackingNumber, 1));
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            request.header("Authorization", "Bearer " + jwtAuthentication.getToken().getTokenValue());
        }
        request.retrieve()
                .onStatus(HttpStatusCode::isError, (clientRequest, response) -> {
                    if (response.getStatusCode().value() == 409) {
                        throw new CapacityUnavailableException("No hay capacidad disponible para el servicio " + serviceId);
                    }
                    throw new IllegalStateException("Catalog respondió con HTTP " + response.getStatusCode().value());
                })
                .toBodilessEntity();
    }

    private record CapacityRequest(String shipmentTrackingNumber, int quantity) {
    }
}
