package cl.rutaexpress.shipments;

import cl.rutaexpress.shipments.entity.Shipment;
import cl.rutaexpress.shipments.entity.ShipmentStatus;
import cl.rutaexpress.shipments.repository.ShipmentRepository;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:http-tests;MODE=Oracle;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "rutaexpress.security.audience=shipments-test",
        "rutaexpress.security.jwk-set-uri=",
        "rutaexpress.catalog.read-timeout=300ms"
})
@AutoConfigureMockMvc
class ShipmentsHttpIntegrationTest {
    private static final RSAKey KEY;
    private static final HttpServer UPSTREAM;
    private static final ExecutorService HTTP_THREADS = Executors.newCachedThreadPool();
    private static final AtomicInteger CATALOG_STATUS = new AtomicInteger(204);
    private static final AtomicInteger CATALOG_CALLS = new AtomicInteger();
    private static final AtomicInteger DELAY_MS = new AtomicInteger();
    private static final AtomicReference<String> BEARER = new AtomicReference<>();
    private static final AtomicReference<String> BODY = new AtomicReference<>();
    private static final AtomicReference<String> METHOD = new AtomicReference<>();
    private static final AtomicReference<String> CONTENT_TYPE = new AtomicReference<>();
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            UPSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            UPSTREAM.setExecutor(HTTP_THREADS);
            UPSTREAM.createContext("/.well-known/openid-configuration", exchange -> {
                byte[] json = ("{\"issuer\":\"" + issuer() + "\",\"jwks_uri\":\"" + issuer() + "/jwks\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, json.length);
                exchange.getResponseBody().write(json);
                exchange.close();
            });
            UPSTREAM.createContext("/jwks", exchange -> {
                byte[] json = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, json.length);
                exchange.getResponseBody().write(json);
                exchange.close();
            });
            UPSTREAM.createContext("/api/catalog/services/1/capacity/decrement", exchange -> {
                CATALOG_CALLS.incrementAndGet();
                BEARER.set(exchange.getRequestHeaders().getFirst("Authorization"));
                CONTENT_TYPE.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                METHOD.set(exchange.getRequestMethod());
                BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                int responseStatus = CATALOG_STATUS.get();
                try {
                    Thread.sleep(DELAY_MS.get());
                    if (responseStatus == 302) exchange.getResponseHeaders().set("Location", issuer() + "/redirect-target");
                    exchange.sendResponseHeaders(responseStatus, -1);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            UPSTREAM.start();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ShipmentsHttpIntegrationTest::issuer);
        registry.add("rutaexpress.catalog.base-url", ShipmentsHttpIntegrationTest::issuer);
    }

    static String issuer() { return "http://127.0.0.1:" + UPSTREAM.getAddress().getPort(); }

    @Autowired MockMvc mvc;
    @Autowired ShipmentRepository repository;

    @BeforeEach
    void reset() {
        repository.deleteAll();
        CATALOG_STATUS.set(204);
        CATALOG_CALLS.set(0);
        DELAY_MS.set(0);
        BEARER.set(null);
    }

    @AfterAll
    static void stopServer() {
        UPSTREAM.stop(0);
        HTTP_THREADS.shutdownNow();
    }

    @Test
    void onlyHealthIsPublicAndDoesNotExposeDetails() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
        for (String path : List.of("/api/shipments", "/api/shipments/1", "/actuator/env", "/error", "/actuator/health/db")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/shipments").contentType("application/json").content(createJson()))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/shipments/1/status").contentType("application/json").content("{\"status\":\"ACEPTADO\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validatesSignatureIssuerAudienceAndExpiration() throws Exception {
        mvc.perform(get("/api/shipments").header("Authorization", "Bearer " + token("CLIENT")))
                .andExpect(status().isOk());
        for (String invalid : List.of(
                token("CLIENT", "wrong-audience", issuer(), Instant.now().plusSeconds(300), KEY),
                token("CLIENT", null, issuer(), Instant.now().plusSeconds(300), KEY),
                token("CLIENT", "shipments-test", "https://wrong.example.test", Instant.now().plusSeconds(300), KEY),
                token("CLIENT", "shipments-test", issuer(), Instant.now().minusSeconds(120), KEY),
                token("CLIENT", "shipments-test", issuer(), Instant.now().plusSeconds(300), new RSAKeyGenerator(2048).keyID("test-key").generate()))) {
            mvc.perform(get("/api/shipments").header("Authorization", "Bearer " + invalid))
                    .andExpect(status().isUnauthorized());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CLIENT", "DISPATCHER", "Auditor", "UNKNOWN", "Admin", "Cliente", "Operador"})
    void mapsRealRolesAndPreservesReadOnlyAuditor(String role) throws Exception {
        String auth = "Bearer " + token(role);
        boolean read = List.of("ADMIN", "CLIENT", "DISPATCHER", "Auditor").contains(role);
        mvc.perform(get("/api/shipments").header("Authorization", auth))
                .andExpect(status().is(read ? 200 : 403));
        mvc.perform(post("/api/shipments").header("Authorization", auth)
                        .contentType("application/json").content(createJson()))
                .andExpect(status().is(List.of("ADMIN", "CLIENT").contains(role) ? 201 : 403));
        Shipment shipment = persist(ShipmentStatus.CREADO, LocalDateTime.now());
        mvc.perform(put("/api/shipments/{id}/status", shipment.getId()).header("Authorization", auth)
                        .contentType("application/json").content("{\"status\":\"CANCELADO\"}"))
                .andExpect(status().is(List.of("ADMIN", "DISPATCHER").contains(role) ? 200 : 403));
    }

    @Test
    void createsReadsValidatesAndReturnsNotFound() throws Exception {
        String auth = "Bearer " + token("CLIENT");
        mvc.perform(post("/api/shipments").header("Authorization", auth)
                        .contentType("application/json").content(createJson()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CREADO"))
                .andExpect(jsonPath("$.trackingNumber").isNotEmpty()).andExpect(jsonPath("$.creadoPor").value("test-subject"));
        Long id = repository.findAll().getFirst().getId();
        mvc.perform(get("/api/shipments/{id}", id).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/shipments/99999999").header("Authorization", auth)).andExpect(status().isNotFound());
        for (String json : List.of("{}", "{", createJson().replace("Ana", "A".repeat(151)))) {
            mvc.perform(post("/api/shipments").header("Authorization", auth).contentType("application/json").content(json))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void filtersPersistedRowsByStatusAndInclusiveDates() throws Exception {
        persist(ShipmentStatus.CREADO, LocalDateTime.of(2026, 9, 13, 23, 59, 59));
        Shipment start = persist(ShipmentStatus.CREADO, LocalDateTime.of(2026, 9, 14, 0, 0));
        persist(ShipmentStatus.CREADO, LocalDateTime.of(2026, 9, 14, 23, 59, 59));
        persist(ShipmentStatus.CREADO, LocalDateTime.of(2026, 9, 15, 0, 0));
        persist(ShipmentStatus.CANCELADO, LocalDateTime.of(2026, 9, 14, 12, 0));
        String auth = "Bearer " + token("CLIENT");
        mvc.perform(get("/api/shipments?status=CREADO&from=2026-09-14&to=2026-09-14").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].id").value(start.getId()));
        mvc.perform(get("/api/shipments?from=2026-09-15").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/shipments?to=2026-09-13").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        for (String query : List.of("status=INVALID", "from=wrong", "from=2026-09-15&to=2026-09-14")) {
            mvc.perform(get("/api/shipments?" + query).header("Authorization", auth)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void acceptsUsingRealHttpAndPropagatesTheExactBearer() throws Exception {
        Shipment shipment = persist(ShipmentStatus.CREADO, LocalDateTime.now());
        String auth = "Bearer " + token("DISPATCHER");
        mvc.perform(put("/api/shipments/{id}/status", shipment.getId()).header("Authorization", auth)
                        .contentType("application/json").content("{\"status\":\"ACEPTADO\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACEPTADO"));
        assertEquals(auth, BEARER.get());
        assertEquals("POST", METHOD.get());
        assertTrue(CONTENT_TYPE.get().startsWith("application/json"));
        assertTrue(BODY.get().contains("\"shipmentTrackingNumber\":\"" + shipment.getTrackingNumber() + "\""));
        assertTrue(BODY.get().contains("\"quantity\":1"));
        assertNotNull(repository.findById(shipment.getId()).orElseThrow().getFechaAceptacion());
        assertEquals(1, CATALOG_CALLS.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {409, 404, 401, 403, 500, 302, 202, 200})
    void catalogFailuresNeverAcceptShipment(int upstreamStatus) throws Exception {
        CATALOG_STATUS.set(upstreamStatus);
        Shipment shipment = persist(ShipmentStatus.CREADO, LocalDateTime.now());
        int expected = upstreamStatus == 409 ? 409 : upstreamStatus == 404 ? 404 : 502;
        mvc.perform(put("/api/shipments/{id}/status", shipment.getId()).header("Authorization", "Bearer " + token("ADMIN"))
                        .contentType("application/json").content("{\"status\":\"ACEPTADO\"}"))
                .andExpect(status().is(expected));
        Shipment unchanged = repository.findById(shipment.getId()).orElseThrow();
        assertEquals(ShipmentStatus.CREADO, unchanged.getEstado());
        assertNull(unchanged.getFechaAceptacion());
    }

    @Test
    void catalogTimeoutRollsBackLocalState() throws Exception {
        DELAY_MS.set(1000);
        Shipment shipment = persist(ShipmentStatus.CREADO, LocalDateTime.now());
        mvc.perform(put("/api/shipments/{id}/status", shipment.getId()).header("Authorization", "Bearer " + token("ADMIN"))
                        .contentType("application/json").content("{\"status\":\"ACEPTADO\"}"))
                .andExpect(status().isBadGateway());
        assertEquals(ShipmentStatus.CREADO, repository.findById(shipment.getId()).orElseThrow().getEstado());
    }

    @Test
    void concurrentAcceptancesOnlyDecrementOnce() throws Exception {
        Shipment shipment = persist(ShipmentStatus.CREADO, LocalDateTime.now());
        String auth = "Bearer " + token("ADMIN");
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> accept = () -> {
                start.await();
                return mvc.perform(put("/api/shipments/{id}/status", shipment.getId()).header("Authorization", auth)
                                .contentType("application/json").content("{\"status\":\"ACEPTADO\"}"))
                        .andReturn().getResponse().getStatus();
            };
            Future<Integer> first = executor.submit(accept);
            Future<Integer> second = executor.submit(accept);
            start.countDown();
            assertEquals(List.of(200, 400), List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                    .stream().sorted().toList());
        }
        assertEquals(1, CATALOG_CALLS.get());
    }

    private Shipment persist(ShipmentStatus status, LocalDateTime created) {
        Shipment shipment = new Shipment();
        shipment.setTrackingNumber("RUTA-" + UUID.randomUUID().toString().substring(0, 12));
        shipment.setClienteId("client-test");
        shipment.setDestinatarioNombre("Ana");
        shipment.setDestinatarioEmail("ana@example.test");
        shipment.setDireccionOrigen("Origen");
        shipment.setDireccionDestino("Destino");
        shipment.setServicioId(1L);
        shipment.setEstado(status);
        shipment.setFechaCreacion(created);
        return repository.saveAndFlush(shipment);
    }

    private static String createJson() {
        return """
                {"clienteId":"client-test","destinatarioNombre":"Ana","destinatarioEmail":"ana@example.test",
                 "direccionOrigen":"Origen","direccionDestino":"Destino","servicioId":1}
                """;
    }

    private static String token(String role) throws Exception {
        return token(role, "shipments-test", issuer(), Instant.now().plusSeconds(300), KEY);
    }

    private static String token(String role, String audience, String issuer, Instant expiry, RSAKey key) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(issuer).subject("test-subject")
                .issueTime(Date.from(Instant.now().minusSeconds(300))).expirationTime(Date.from(expiry))
                .claim("roles", List.of(role));
        if (audience != null) claims.audience(List.of("another-api", audience));
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT)
                .keyID(key.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
