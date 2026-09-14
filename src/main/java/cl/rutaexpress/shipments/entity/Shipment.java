package cl.rutaexpress.shipments.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "SHIPMENTS", uniqueConstraints = @UniqueConstraint(name = "UK_SHIPMENT_TRACKING", columnNames = "TRACKING_NUMBER"))
@Getter
@Setter
@NoArgsConstructor
public class Shipment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "TRACKING_NUMBER", nullable = false, unique = true, length = 32)
    private String trackingNumber;

    @Column(name = "CLIENTE_ID", nullable = false, length = 100)
    private String clienteId;

    @Column(name = "DESTINATARIO_NOMBRE", nullable = false, length = 150)
    private String destinatarioNombre;

    @Column(name = "DESTINATARIO_EMAIL", nullable = false, length = 254)
    private String destinatarioEmail;

    @Column(name = "DIRECCION_ORIGEN", nullable = false, length = 300)
    private String direccionOrigen;

    @Column(name = "DIRECCION_DESTINO", nullable = false, length = 300)
    private String direccionDestino;

    @Column(name = "SERVICIO_ID", nullable = false)
    private Long servicioId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ESTADO", nullable = false, length = 20)
    private ShipmentStatus estado;

    @Column(name = "FECHA_CREACION", nullable = false)
    private LocalDateTime fechaCreacion;

    private LocalDateTime fechaAceptacion;
    private LocalDateTime fechaEntrega;

    @Column(name = "CREADO_POR", length = 150)
    private String creadoPor;
}
