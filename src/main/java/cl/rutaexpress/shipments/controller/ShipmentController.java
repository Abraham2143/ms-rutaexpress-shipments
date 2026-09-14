package cl.rutaexpress.shipments.controller;

import cl.rutaexpress.shipments.dto.CreateShipmentRequest;
import cl.rutaexpress.shipments.dto.ShipmentResponse;
import cl.rutaexpress.shipments.dto.UpdateShipmentStatusRequest;
import cl.rutaexpress.shipments.entity.ShipmentStatus;
import cl.rutaexpress.shipments.service.ShipmentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {
    private final ShipmentService service;

    public ShipmentController(ShipmentService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShipmentResponse create(@Valid @RequestBody CreateShipmentRequest request, Authentication authentication) {
        return service.create(request, authentication.getName());
    }

    @GetMapping("/{id}")
    public ShipmentResponse findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @PutMapping("/{id}/status")
    public ShipmentResponse updateStatus(@PathVariable Long id,
                                         @Valid @RequestBody UpdateShipmentStatusRequest request) {
        return service.updateStatus(id, request.status());
    }

    @GetMapping
    public List<ShipmentResponse> search(@RequestParam(required = false) ShipmentStatus status,
                                         @RequestParam(required = false) LocalDate from,
                                         @RequestParam(required = false) LocalDate to) {
        return service.search(status, from, to);
    }
}
