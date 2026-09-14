package cl.rutaexpress.shipments.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateShipmentRequest(
        @NotBlank String clienteId,
        @NotBlank String destinatarioNombre,
        @NotBlank @Email String destinatarioEmail,
        @NotBlank String direccionOrigen,
        @NotBlank String direccionDestino,
        @NotNull @Positive Long servicioId
) {
}
