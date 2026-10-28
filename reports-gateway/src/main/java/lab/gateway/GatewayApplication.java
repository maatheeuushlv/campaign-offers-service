package lab.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Endpoint 1 (GET preferences) and endpoint 4 (POST insert/remove recommendation). */
@SpringBootApplication(scanBasePackages = "lab")
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
