package lab.common;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LabCommonConfig {

    @Bean
    public JwtService jwtService(@Value("${lab.jwt.secret}") String secret,
                                 @Value("${lab.jwt.cnpj-claim:cnpj}") String cnpjClaim) {
        return new JwtService(secret, cnpjClaim);
    }

    /** "Today" for exhibitionStartDate/exhibitionEndDate is evaluated in this zone. */
    @Bean
    public Clock clock(@Value("${lab.zone:America/Sao_Paulo}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
