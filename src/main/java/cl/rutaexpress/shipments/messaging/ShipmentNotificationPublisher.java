package cl.rutaexpress.shipments.messaging;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ShipmentNotificationPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(ShipmentNotificationPublisher.class);
    private final RabbitTemplate rabbitTemplate;
    private final JsonMapper jsonMapper;

    public ShipmentNotificationPublisher(RabbitTemplate rabbitTemplate, JsonMapper jsonMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.jsonMapper = jsonMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(ShipmentNotification notification) {
        try {
            var message = MessageBuilder.withBody(jsonMapper.writeValueAsBytes(notification))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setContentEncoding(StandardCharsets.UTF_8.name())
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .build();
            rabbitTemplate.send("cmd.direct", "email.send", message);
            // Submission is not a publisher confirm or proof of consumer processing.
            LOG.info("shipmentId={} status={} result=notification_submitted",
                    notification.shipmentId(), notification.status());
        } catch (RuntimeException exception) {
            // Oracle has already committed. Keep the REST outcome and omit sensitive details.
            // There is no retry/outbox in this implementation; delivery can be lost.
            LOG.error("shipmentId={} status={} result=notification_publish_failed error={}",
                    notification.shipmentId(), notification.status(), exception.getClass().getSimpleName());
        }
    }
}
