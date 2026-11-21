package cl.rutaexpress.shipments.messaging;

import cl.rutaexpress.shipments.entity.ShipmentStatus;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class ShipmentEventKafkaPublisherTest {
    private static final String TOPIC = "rutaexpress.shipments.events.v1";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ShipmentEventKafkaPublisher publisher = new ShipmentEventKafkaPublisher(kafka, mapper, TOPIC);

    private static ShipmentNotification notification(ShipmentStatus status, String instant) {
        return new ShipmentNotification(42L, "RUTA-TEST", status, "Ana Pérez", "ana@example.test",
                "Dejar en conserjería", Instant.parse(instant));
    }

    @Test
    void publishesAnalyticsContractKeyedByShipmentIdWithoutPersonalData(CapturedOutput output) {
        givenSendSucceeds();

        publisher.publish(notification(ShipmentStatus.EN_RUTA, "2026-11-20T23:00:00Z"));

        var payload = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(TOPIC), eq("42"), payload.capture());
        verifyNoMoreInteractions(kafka);
        var json = mapper.readTree(payload.getValue());
        assertEquals("1.0", json.get("schemaVersion").asString());
        assertEquals("SHIPMENT_STATUS_CHANGED", json.get("eventType").asString());
        assertEquals("2026-11-20T23:00:00Z", json.get("occurredAt").asString());
        assertEquals("42", json.get("shipmentId").asString());
        assertEquals("EN_RUTA", json.get("status").asString());
        assertTrue(json.get("eventId").asString().startsWith("evt-"));
        assertTrue(json.get("eventId").asString().length() <= 100);
        assertTrue(json.get("correlationId").isNull());
        assertEquals(7, json.size());
        assertFalse(payload.getValue().contains("ana@example.test"));
        assertFalse(payload.getValue().contains("Ana Pérez"));
        assertFalse(payload.getValue().contains("conserjería"));
        assertTrue(output.getOut().contains("result=kafka_event_published"));
        assertTrue(output.getOut().contains("partition=1"));
    }

    @Test
    void creationUsesShipmentCreatedEventType() {
        var event = ShipmentEventV1.from(notification(ShipmentStatus.CREADO, "2026-11-20T23:00:00Z"));
        assertEquals("SHIPMENT_CREATED", event.eventType());
        assertEquals("CREADO", event.status());
    }

    @Test
    void eventIdIsDeterministicPerShipmentStatusAndInstant() {
        var first = ShipmentEventV1.from(notification(ShipmentStatus.ACEPTADO, "2026-11-20T23:00:00Z"));
        var same = ShipmentEventV1.from(notification(ShipmentStatus.ACEPTADO, "2026-11-20T23:00:00Z"));
        var otherStatus = ShipmentEventV1.from(notification(ShipmentStatus.EN_BODEGA, "2026-11-20T23:00:00Z"));
        var otherInstant = ShipmentEventV1.from(notification(ShipmentStatus.ACEPTADO, "2026-11-20T23:00:01Z"));
        assertEquals(first.eventId(), same.eventId());
        assertNotEquals(first.eventId(), otherStatus.eventId());
        assertNotEquals(first.eventId(), otherInstant.eventId());
    }

    @Test
    void asynchronousBrokerFailureIsLoggedWithoutDetailsOrPersonalData(CapturedOutput output) {
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new KafkaException("secret broker detail"));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(failed);

        assertDoesNotThrow(() -> publisher.publish(notification(ShipmentStatus.ENTREGADO, "2026-11-20T23:00:00Z")));

        assertTrue(output.getOut().contains("result=kafka_event_publish_failed"));
        assertTrue(output.getOut().contains("shipmentId=42"));
        assertFalse(output.getAll().contains("secret broker detail"));
        assertFalse(output.getAll().contains("ana@example.test"));
    }

    @Test
    void synchronousFailureDoesNotPropagateToTheCommittedRequest(CapturedOutput output) {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenThrow(new KafkaException("metadata not available after 5000 ms"));

        assertDoesNotThrow(() -> publisher.publish(notification(ShipmentStatus.ACEPTADO, "2026-11-20T23:00:00Z")));

        assertTrue(output.getOut().contains("result=kafka_event_publish_failed"));
        assertFalse(output.getAll().contains("metadata not available"));
    }

    @Test
    void publisherExistsOnlyWhenKafkaIsEnabled() {
        var runner = new ApplicationContextRunner()
                .withBean(KafkaTemplate.class, () -> kafka)
                .withBean(JsonMapper.class, () -> mapper)
                .withUserConfiguration(ShipmentEventKafkaPublisher.class)
                .withPropertyValues("rutaexpress.kafka.shipments-topic=" + TOPIC);

        runner.run(context -> assertThat(context).doesNotHaveBean(ShipmentEventKafkaPublisher.class));
        runner.withPropertyValues("rutaexpress.kafka.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ShipmentEventKafkaPublisher.class));
        runner.withPropertyValues("rutaexpress.kafka.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ShipmentEventKafkaPublisher.class));
    }

    private void givenSendSucceeds() {
        @SuppressWarnings("unchecked")
        SendResult<String, String> result = mock(SendResult.class);
        RecordMetadata metadata = mock(RecordMetadata.class);
        when(result.getRecordMetadata()).thenReturn(metadata);
        when(metadata.partition()).thenReturn(1);
        when(metadata.offset()).thenReturn(7L);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(result));
    }
}
