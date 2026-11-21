package cl.rutaexpress.shipments.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publica en Kafka cada creación y cambio de estado de un envío, después del commit en Oracle.
 * Escucha el mismo evento interno que ShipmentNotificationPublisher (RabbitMQ) y es
 * independiente de él: un fallo de Kafka no afecta el correo ni la respuesta REST.
 * Solo existe con KAFKA_ENABLED=true; los tópicos los crea ms-rutaexpress-audit (kafka-admin).
 */
@Component
@ConditionalOnProperty(name = "rutaexpress.kafka.enabled", havingValue = "true")
public class ShipmentEventKafkaPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(ShipmentEventKafkaPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final String topic;

    public ShipmentEventKafkaPublisher(KafkaTemplate<String, String> kafkaTemplate, JsonMapper jsonMapper,
                                       @Value("${rutaexpress.kafka.shipments-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(ShipmentNotification notification) {
        ShipmentEventV1 event = ShipmentEventV1.from(notification);
        try {
            String payload = jsonMapper.writeValueAsString(event);
            // Key = shipmentId: todos los eventos de un envío van a la misma partición, en orden.
            kafkaTemplate.send(topic, event.shipmentId(), payload).whenComplete((result, exception) -> {
                if (exception == null) {
                    LOG.info("shipmentId={} status={} eventId={} topic={} partition={} offset={} result=kafka_event_published",
                            event.shipmentId(), event.status(), event.eventId(), topic,
                            result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                } else {
                    logFailure(event, exception);
                }
            });
        } catch (RuntimeException exception) {
            // Oracle ya confirmó el cambio. Se registra sin detalles sensibles y no hay reintento.
            logFailure(event, exception);
        }
    }

    private void logFailure(ShipmentEventV1 event, Throwable exception) {
        Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
        LOG.error("shipmentId={} status={} eventId={} topic={} result=kafka_event_publish_failed error={}",
                event.shipmentId(), event.status(), event.eventId(), topic, cause.getClass().getSimpleName());
    }
}
