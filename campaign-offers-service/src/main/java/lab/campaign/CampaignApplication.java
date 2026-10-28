package lab.campaign;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Endpoint 3: recommendations (campaign offers) valid today for logon + CNPJ. */
@SpringBootApplication(scanBasePackages = "lab")
public class CampaignApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampaignApplication.class, args);
    }
}
