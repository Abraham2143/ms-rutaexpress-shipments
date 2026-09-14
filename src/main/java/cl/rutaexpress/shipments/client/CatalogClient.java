package cl.rutaexpress.shipments.client;

public interface CatalogClient {
    void decrementCapacity(Long serviceId, String shipmentTrackingNumber);
}
