package vassago;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration;

@SpringBootApplication(exclude = {
        R2dbcAutoConfiguration.class,
})
public class VassagoApplication {
    public static void main(String[] args) {
        SpringApplication.run(VassagoApplication.class, args);
    }

}
