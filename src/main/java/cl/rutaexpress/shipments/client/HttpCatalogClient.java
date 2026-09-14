package cl.rutaexpress.shipments.client;

import cl.rutaexpress.shipments.exception.CapacityUnavailableException;
import cl.rutaexpress.shipments.exception.CatalogIntegrationException;
import cl.rutaexpress.shipments.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !authentication.isAuthenticated()) {
            throw new CatalogIntegrationException("No hay JWT autenticado para llamar a Catalog");
        }
        try {
            var responseEntity = restClient.post()
                    .uri(uriBuilder -> uriBuilder.path(capacityPath).build(serviceId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(jwtAuthentication.getToken().getTokenValue()))
                    .body(new CapacityRequest(shipmentTrackingNumber, 1))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (clientRequest, response) -> {
                        if (response.getStatusCode().value() == 409) {
                            throw new CapacityUnavailableException("No hay capacidad disponible para el servicio " + serviceId);
                        }
                        if (response.getStatusCode().value() == 404) {
                            throw new ResourceNotFoundException("Servicio no encontrado en Catalog: " + serviceId);
                        }
                        throw new CatalogIntegrationException("Catalog respondió con HTTP " + response.getStatusCode().value());
                    })
                    .toBodilessEntity();
            if (responseEntity.getStatusCode().value() != 204) {
                throw new CatalogIntegrationException("Catalog no confirmó el descuento de capacidad con HTTP 204");
            }
        } catch (RestClientException exception) {
            // Do not expose upstream bodies, URLs or tokens in the public response.
            throw new CatalogIntegrationException("No fue posible confirmar la capacidad con Catalog");
        }
    }

    private record CapacityRequest(String shipmentTrackingNumber, int quantity) {
    }
}
