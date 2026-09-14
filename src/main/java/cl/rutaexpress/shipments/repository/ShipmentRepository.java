package cl.rutaexpress.shipments.repository;

import cl.rutaexpress.shipments.entity.Shipment;
import cl.rutaexpress.shipments.entity.ShipmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {
    boolean existsByTrackingNumber(String trackingNumber);

    Optional<Shipment> findByTrackingNumber(String trackingNumber);

    @Query("""
        select s from Shipment s
        where (:status is null or s.estado = :status)
          and (:fromDate is null or s.fechaCreacion >= :fromDate)
          and (:toDate is null or s.fechaCreacion <= :toDate)
        order by s.fechaCreacion desc
        """)
    List<Shipment> search(@Param("status") ShipmentStatus status,
                          @Param("fromDate") LocalDateTime fromDate,
                          @Param("toDate") LocalDateTime toDate);
}
