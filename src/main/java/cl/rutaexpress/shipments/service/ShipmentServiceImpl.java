package cl.rutaexpress.shipments.service;

import cl.rutaexpress.shipments.client.CatalogClient;
import cl.rutaexpress.shipments.dto.CreateShipmentRequest;
import cl.rutaexpress.shipments.dto.ShipmentResponse;
import cl.rutaexpress.shipments.entity.Shipment;
import cl.rutaexpress.shipments.entity.ShipmentStatus;
import cl.rutaexpress.shipments.exception.InvalidShipmentTransitionException;
import cl.rutaexpress.shipments.exception.ResourceNotFoundException;
import cl.rutaexpress.shipments.repository.ShipmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ShipmentServiceImpl implements ShipmentService {
    private static final Map<ShipmentStatus, EnumSet<ShipmentStatus>> TRANSITIONS = transitions();

    private final ShipmentRepository repository;
    private final CatalogClient catalogClient;

    public ShipmentServiceImpl(ShipmentRepository repository, CatalogClient catalogClient) {
        this.repository = repository;
        this.catalogClient = catalogClient;
    }

    @Override
    @Transactional
    public ShipmentResponse create(CreateShipmentRequest request, String createdBy) {
        Shipment shipment = new Shipment();
        shipment.setTrackingNumber(generateTrackingNumber());
        shipment.setClienteId(request.clienteId());
        shipment.setDestinatarioNombre(request.destinatarioNombre());
        shipment.setDestinatarioEmail(request.destinatarioEmail());
        shipment.setDireccionOrigen(request.direccionOrigen());
        shipment.setDireccionDestino(request.direccionDestino());
        shipment.setServicioId(request.servicioId());
        shipment.setEstado(ShipmentStatus.CREADO);
        shipment.setFechaCreacion(LocalDateTime.now());
        shipment.setCreadoPor(createdBy);
        return ShipmentResponse.from(repository.save(shipment));
    }

    @Override
    @Transactional(readOnly = true)
    public ShipmentResponse findById(Long id) {
        return ShipmentResponse.from(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Envío no encontrado: " + id)));
    }

    @Override
    @Transactional
    public ShipmentResponse updateStatus(Long id, ShipmentStatus targetStatus) {
        Shipment shipment = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Envío no encontrado: " + id));
        ShipmentStatus currentStatus = shipment.getEstado();
        if (!TRANSITIONS.getOrDefault(currentStatus, EnumSet.noneOf(ShipmentStatus.class)).contains(targetStatus)) {
            throw new InvalidShipmentTransitionException(
                    "Transición no permitida: " + currentStatus + " -> " + targetStatus);
        }
        if (targetStatus == ShipmentStatus.ACEPTADO) {
            catalogClient.decrementCapacity(shipment.getServicioId(), shipment.getTrackingNumber());
            shipment.setFechaAceptacion(LocalDateTime.now());
        }
        if (targetStatus == ShipmentStatus.EN_RUTA && shipment.getFechaAceptacion() == null) {
            throw new InvalidShipmentTransitionException("EN_RUTA requiere aceptación previa registrada");
        }
        if (targetStatus == ShipmentStatus.ENTREGADO) {
            shipment.setFechaEntrega(LocalDateTime.now());
        }
        shipment.setEstado(targetStatus);
        return ShipmentResponse.from(repository.save(shipment));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShipmentResponse> search(ShipmentStatus status, LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from no puede ser posterior a to");
        }
        LocalDateTime fromDate = from == null ? null : from.atStartOfDay();
        // An exclusive next-day bound avoids rounding the last nanosecond in Oracle TIMESTAMP.
        LocalDateTime toDate = to == null ? null : to.plusDays(1).atStartOfDay();
        return repository.search(status, fromDate, toDate).stream().map(ShipmentResponse::from).toList();
    }

    private String generateTrackingNumber() {
        String tracking;
        do {
            tracking = "RUTA-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        } while (repository.existsByTrackingNumber(tracking));
        return tracking;
    }

    private static Map<ShipmentStatus, EnumSet<ShipmentStatus>> transitions() {
        EnumMap<ShipmentStatus, EnumSet<ShipmentStatus>> transitions = new EnumMap<>(ShipmentStatus.class);
        transitions.put(ShipmentStatus.CREADO, EnumSet.of(ShipmentStatus.ACEPTADO, ShipmentStatus.CANCELADO));
        transitions.put(ShipmentStatus.ACEPTADO, EnumSet.of(ShipmentStatus.EN_BODEGA, ShipmentStatus.CANCELADO));
        transitions.put(ShipmentStatus.EN_BODEGA, EnumSet.of(ShipmentStatus.EN_RUTA, ShipmentStatus.CANCELADO));
        transitions.put(ShipmentStatus.EN_RUTA, EnumSet.of(ShipmentStatus.ENTREGADO));
        transitions.put(ShipmentStatus.ENTREGADO, EnumSet.noneOf(ShipmentStatus.class));
        transitions.put(ShipmentStatus.CANCELADO, EnumSet.noneOf(ShipmentStatus.class));
        return transitions;
    }
}
