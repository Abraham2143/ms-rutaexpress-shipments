package cl.rutaexpress.shipments.messaging;

import cl.rutaexpress.shipments.client.CatalogClient;
import cl.rutaexpress.shipments.entity.Shipment;
import cl.rutaexpress.shipments.entity.ShipmentStatus;
import cl.rutaexpress.shipments.exception.InvalidShipmentTransitionException;
import cl.rutaexpress.shipments.repository.ShipmentRepository;
import cl.rutaexpress.shipments.service.ShipmentService;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:notification-tests;MODE=Oracle;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
        "rutaexpress.security.audience=shipments-test",
        "spring.rabbitmq.username=test", "spring.rabbitmq.password=test",
        "management.health.rabbit.enabled=false"
})
class ShipmentNotificationTransactionTest {
    @Autowired ShipmentService service;
    @Autowired ShipmentRepository repository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ApplicationEventPublisher events;
    @Autowired ApplicationContext context;
    @Autowired JsonMapper mapper;
    @MockitoBean RabbitTemplate rabbit;
    @MockitoBean CatalogClient catalog;

    @BeforeEach void clean() { repository.deleteAll(); clearInvocations(rabbit); }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"ACEPTADO", "EN_RUTA", "ENTREGADO"})
    void publishesOnlyAfterCommitWithCommittedStatusVisibleFromAnotherTransaction(ShipmentStatus target) {
        Shipment original = persist(switch (target) {
            case ACEPTADO -> ShipmentStatus.CREADO;
            case EN_RUTA -> ShipmentStatus.EN_BODEGA;
            default -> ShipmentStatus.EN_RUTA;
        });
        doAnswer(invocation -> {
            var independent = new TransactionTemplate(transactionManager);
            independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertEquals(target, independent.execute(status -> repository.findById(original.getId()).orElseThrow().getEstado()));
            Message message = invocation.getArgument(2);
            var json = mapper.readTree(message.getBody());
            assertEquals(original.getId().longValue(), json.get("shipmentId").asLong());
            assertEquals(target.name(), json.get("status").asString());
            return null;
        }).when(rabbit).send(eq("cmd.direct"), eq("email.send"), any(Message.class));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.updateStatus(original.getId(), target);
            verifyNoInteractions(rabbit);
        });
        verify(rabbit).send(eq("cmd.direct"), eq("email.send"), any(Message.class));
        verifyNoMoreInteractions(rabbit);
    }

    @Test void rollbackDiscardsNotificationAndStateChange() {
        Shipment original = persist(ShipmentStatus.EN_RUTA);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.updateStatus(original.getId(), ShipmentStatus.ENTREGADO);
            verifyNoInteractions(rabbit);
            status.setRollbackOnly();
        });
        verifyNoInteractions(rabbit);
        assertEquals(ShipmentStatus.EN_RUTA, repository.findById(original.getId()).orElseThrow().getEstado());
    }

    @Test void commitFailureDoesNotPublishRegisteredEvent() {
        Shipment original = persist(ShipmentStatus.EN_RUTA);
        assertThrows(RuntimeException.class, () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.updateStatus(original.getId(), ShipmentStatus.ENTREGADO);
            repository.findById(original.getId()).orElseThrow().setDestinatarioNombre(null);
            verifyNoInteractions(rabbit);
        }));
        verifyNoInteractions(rabbit);
        assertEquals(ShipmentStatus.EN_RUTA, repository.findById(original.getId()).orElseThrow().getEstado());
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"EN_BODEGA", "CANCELADO"})
    void nonNotifyingStatesDoNotPublish(ShipmentStatus target) {
        Shipment original = persist(ShipmentStatus.ACEPTADO);
        service.updateStatus(original.getId(), target);
        verifyNoInteractions(rabbit);
    }

    @Test void invalidTransitionDoesNotPublish() {
        Shipment original = persist(ShipmentStatus.CREADO);
        assertThrows(InvalidShipmentTransitionException.class,
                () -> service.updateStatus(original.getId(), ShipmentStatus.EN_RUTA));
        verifyNoInteractions(rabbit);
    }

    @Test void eventOutsideTransactionIsNotSentAndApplicationHasNoTopologyBeans() {
        events.publishEvent(ShipmentNotification.from(persist(ShipmentStatus.ACEPTADO)));
        verifyNoInteractions(rabbit);
        assertTrue(context.getBeansOfType(AmqpAdmin.class).isEmpty());
        assertTrue(context.getBeansOfType(Declarable.class).isEmpty());
        assertTrue(context.getBeansOfType(Declarables.class).isEmpty());
    }

    private Shipment persist(ShipmentStatus state) {
        Shipment shipment = new Shipment();
        shipment.setTrackingNumber("RUTA-" + UUID.randomUUID().toString().substring(0, 12));
        shipment.setClienteId("test-client");
        shipment.setDestinatarioNombre("Ana Pérez");
        shipment.setDestinatarioEmail("ana@example.test");
        shipment.setDireccionOrigen("Origen");
        shipment.setDireccionDestino("Destino");
        shipment.setServicioId(1L);
        shipment.setEstado(state);
        shipment.setFechaCreacion(LocalDateTime.now());
        shipment.setFechaAceptacion(state == ShipmentStatus.CREADO ? null : LocalDateTime.now());
        return repository.saveAndFlush(shipment);
    }
}
