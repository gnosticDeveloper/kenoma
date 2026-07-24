package raum;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(basePackages = {"raum", "common"})
@EnableR2dbcRepositories
@EnableScheduling
public class RaumApplication {

	public static void main(String[] args) {
		SpringApplication.run(RaumApplication.class, args);
	}

}
