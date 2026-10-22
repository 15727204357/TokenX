package com.tokenx.order.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("tokenx.seckill")
public class SeckillProperties {
  public enum Mode { SYNC, ASYNC }
  @NotNull private Mode mode=Mode.SYNC;
  @NotNull private Duration admissionTtl=Duration.ofHours(48);
  public Mode getMode(){return mode;} public void setMode(Mode v){mode=v;}
  public Duration getAdmissionTtl(){return admissionTtl;} public void setAdmissionTtl(Duration v){admissionTtl=v;}
}
