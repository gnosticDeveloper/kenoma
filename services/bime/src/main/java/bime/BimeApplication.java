package bime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {R2dbcAutoConfiguration.class})
@EnableScheduling
public class BimeApplication {

	public static void main(String[] args) {
		SpringApplication.run(BimeApplication.class, args);
	}

}
