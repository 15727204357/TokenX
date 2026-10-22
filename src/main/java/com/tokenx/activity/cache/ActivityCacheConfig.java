package com.tokenx.activity.cache;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ActivityCacheProperties.class)
public class ActivityCacheConfig {

  @Bean
  ActivityCachePropertiesValidator activityCachePropertiesValidator(
      ActivityCacheProperties properties) {
    return new ActivityCachePropertiesValidator(properties);
  }

  /** Runs after binding and before the application accepts requests. */
  record ActivityCachePropertiesValidator(ActivityCacheProperties properties) {
    ActivityCachePropertiesValidator {
      properties.validate();
    }
  }
}
