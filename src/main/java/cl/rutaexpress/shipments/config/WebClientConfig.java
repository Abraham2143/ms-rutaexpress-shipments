package cl.rutaexpress.shipments.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class WebClientConfig {
    @Bean
    RestClient.Builder restClientBuilder(
            @Value("${rutaexpress.catalog.connect-timeout}") Duration connectTimeout,
            @Value("${rutaexpress.catalog.read-timeout}") Duration readTimeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory);
    }
}
