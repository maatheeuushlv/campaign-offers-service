package lab.reports;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Endpoint 2: eligible reports/features for logon + CNPJ, cached in Redis for 12h. */
@SpringBootApplication(scanBasePackages = "lab")
public class ReportsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReportsApplication.class, args);
    }
}
