package cl.rutaexpress.shipments;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:shipments;MODE=Oracle;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
		"spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test"
})
class MsRutaexpressShipmentsApplicationTests {

	@Test
	void contextLoads() {
	}

}
