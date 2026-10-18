package cl.rutaexpress.shipments.messaging;

import cl.rutaexpress.shipments.entity.ShipmentStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class ShipmentNotificationPublisherTest {
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ShipmentNotificationPublisher publisher = new ShipmentNotificationPublisher(rabbit, mapper);
    private final ShipmentNotification event = new ShipmentNotification(42L, "RUTA-TEST", ShipmentStatus.ACEPTADO,
            "Ana Pérez", "ana@example.test", Instant.parse("2026-10-16T02:00:00Z"));

    @Test
    void sendsExactJsonAndPersistentUtf8MetadataToOfficialRoute() {
        publisher.publish(event);
        var captured = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq("cmd.direct"), eq("email.send"), captured.capture());
        verifyNoMoreInteractions(rabbit);
        Message message = captured.getValue();
        var json = mapper.readTree(message.getBody());
        assertEquals(mapper.readTree("""
                {"shipmentId":42,"trackingNumber":"RUTA-TEST","status":"ACEPTADO",
                 "destinatarioNombre":"Ana Pérez","destinatarioEmail":"ana@example.test",
                 "timestamp":"2026-10-16T02:00:00Z"}
                """), json);
        assertEquals("application/json", message.getMessageProperties().getContentType());
        assertEquals("UTF-8", message.getMessageProperties().getContentEncoding());
        assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getDeliveryMode());
        assertTrue(new String(message.getBody(), StandardCharsets.UTF_8).contains("Ana Pérez"));
    }

    @Test
    void brokerFailureIsLoggedWithoutLeakingDetailsOrChangingCommittedOutcome(CapturedOutput output) {
        doThrow(new AmqpConnectException(new java.net.ConnectException("secret connection detail")))
                .when(rabbit).send(anyString(), anyString(), any(Message.class));
        assertDoesNotThrow(() -> publisher.publish(event));
        assertTrue(output.getOut().contains("notification_publish_failed"));
        assertTrue(output.getOut().contains("shipmentId=42"));
        assertFalse(output.getAll().contains("secret connection detail"));
        assertFalse(output.getAll().contains("ana@example.test"));
    }
}
