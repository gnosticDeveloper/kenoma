package vassago;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {
        R2dbcAutoConfiguration.class,
})
@ComponentScan(basePackages = {"vassago", "common"})
@EnableScheduling
public class VassagoApplication {
    public static void main(String[] args) {
        SpringApplication.run(VassagoApplication.class, args);
    }
}