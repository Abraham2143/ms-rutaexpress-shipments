package cl.rutaexpress.shipments.service;

import cl.rutaexpress.shipments.dto.CreateShipmentRequest;
import cl.rutaexpress.shipments.dto.ShipmentResponse;
import cl.rutaexpress.shipments.entity.ShipmentStatus;

import java.time.LocalDate;
import java.util.List;

public interface ShipmentService {
    ShipmentResponse create(CreateShipmentRequest request, String createdBy);
    ShipmentResponse findById(Long id);
    ShipmentResponse updateStatus(Long id, ShipmentStatus targetStatus);
    List<ShipmentResponse> search(ShipmentStatus status, LocalDate from, LocalDate to);
}
