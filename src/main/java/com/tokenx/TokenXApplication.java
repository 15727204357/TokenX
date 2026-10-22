package com.tokenx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.tokenx.order.config.SeckillProperties;

@SpringBootApplication
@EnableConfigurationProperties(SeckillProperties.class)
public class TokenXApplication {

  public static void main(String[] args) {
    SpringApplication.run(TokenXApplication.class, args);
  }
}
