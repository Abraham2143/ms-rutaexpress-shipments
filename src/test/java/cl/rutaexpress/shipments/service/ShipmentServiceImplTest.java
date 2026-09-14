package cl.rutaexpress.shipments.service;

import cl.rutaexpress.shipments.client.CatalogClient;
import cl.rutaexpress.shipments.dto.CreateShipmentRequest;
import cl.rutaexpress.shipments.entity.Shipment;
import cl.rutaexpress.shipments.entity.ShipmentStatus;
import cl.rutaexpress.shipments.exception.CapacityUnavailableException;
import cl.rutaexpress.shipments.exception.InvalidShipmentTransitionException;
import cl.rutaexpress.shipments.exception.ResourceNotFoundException;
import cl.rutaexpress.shipments.repository.ShipmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShipmentServiceImplTest {
    @Mock
    private ShipmentRepository repository;
    @Mock
    private CatalogClient catalogClient;

    private ShipmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ShipmentServiceImpl(repository, catalogClient);
    }

    @Test
    void createsShipmentInCreatedStatus() {
        CreateShipmentRequest request = new CreateShipmentRequest(
                "cliente-001", "Ana Pérez", "ana@example.com",
                "Bodega Central", "Av. Ejemplo 123", 1L);
        when(repository.existsByTrackingNumber(any())).thenReturn(false);
        when(repository.save(any(Shipment.class))).thenAnswer(invocation -> {
            Shipment shipment = invocation.getArgument(0);
            shipment.setId(10L);
            return shipment;
        });

        var response = service.create(request, "cliente-001");

        assertEquals(ShipmentStatus.CREADO, response.status());
        assertTrue(response.trackingNumber().startsWith("RUTA-"));
        verify(repository).save(any(Shipment.class));
    }

    @Test
    void acceptsShipmentAndDecrementsCapacity() {
        Shipment shipment = shipment(ShipmentStatus.CREADO);
        when(repository.findById(10L)).thenReturn(Optional.of(shipment));
        when(repository.save(any(Shipment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.updateStatus(10L, ShipmentStatus.ACEPTADO);

        assertEquals(ShipmentStatus.ACEPTADO, response.status());
        assertNotNull(response.fechaAceptacion());
        verify(catalogClient).decrementCapacity(1L, "RUTA-TEST");
    }

    @Test
    void rejectsCreatedToEnRuta() {
        when(repository.findById(10L)).thenReturn(Optional.of(shipment(ShipmentStatus.CREADO)));

        assertThrows(InvalidShipmentTransitionException.class,
                () -> service.updateStatus(10L, ShipmentStatus.EN_RUTA));
        verifyNoInteractions(catalogClient);
    }

    @Test
    void returnsNotFoundForUnknownShipment() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.findById(99L));
    }

    @Test
    void keepsShipmentCreatedWhenCatalogHasNoCapacity() {
        when(repository.findById(10L)).thenReturn(Optional.of(shipment(ShipmentStatus.CREADO)));
        doThrow(new CapacityUnavailableException("sin capacidad"))
                .when(catalogClient).decrementCapacity(1L, "RUTA-TEST");

        assertThrows(CapacityUnavailableException.class,
                () -> service.updateStatus(10L, ShipmentStatus.ACEPTADO));
        assertEquals(ShipmentStatus.CREADO, repository.findById(10L).orElseThrow().getEstado());
        verify(repository, never()).save(any());
    }

    private Shipment shipment(ShipmentStatus status) {
        Shipment shipment = new Shipment();
        shipment.setId(10L);
        shipment.setTrackingNumber("RUTA-TEST");
        shipment.setServicioId(1L);
        shipment.setEstado(status);
        return shipment;
    }
}
