package cl.rutaexpress.shipments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.TimeZone;

@SpringBootApplication
public class MsRutaexpressShipmentsApplication {

	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone("America/Santiago"));
		SpringApplication.run(MsRutaexpressShipmentsApplication.class, args);
	}
}