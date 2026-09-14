package cl.rutaexpress.shipments.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateShipmentRequest(
        @NotBlank @Size(max = 100) String clienteId,
        @NotBlank @Size(max = 150) String destinatarioNombre,
        @NotBlank @Email @Size(max = 254) String destinatarioEmail,
        @NotBlank @Size(max = 300) String direccionOrigen,
        @NotBlank @Size(max = 300) String direccionDestino,
        @NotNull @Positive Long servicioId
) {
}
