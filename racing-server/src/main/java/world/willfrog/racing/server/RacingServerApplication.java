package world.willfrog.racing.server;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableDubbo
public class RacingServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RacingServerApplication.class, args);
    }
}
