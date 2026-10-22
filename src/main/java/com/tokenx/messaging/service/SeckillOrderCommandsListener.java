package com.tokenx.messaging.service;

import tools.jackson.databind.ObjectMapper;
import com.tokenx.messaging.config.MessagingProperties;
import com.tokenx.messaging.model.SeckillOrderCommand;
import com.tokenx.order.service.SeckillOrderCommandProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${tokenx.messaging.kafka-enabled:true}' == 'true' && '${tokenx.seckill.mode:SYNC}' == 'ASYNC'")
public class SeckillOrderCommandsListener {
  private final ObjectMapper json; private final SeckillOrderCommandProcessor processor;
  public SeckillOrderCommandsListener(ObjectMapper json,SeckillOrderCommandProcessor processor){this.json=json;this.processor=processor;}
  @KafkaListener(id="seckillOrderCommandsListener",topics="${tokenx.messaging.seckill-orders-topic:tokenx.seckill-order-commands.v1}",groupId="${tokenx.messaging.seckill-consumer-group-id:tokenx-seckill-orders-v1}",autoStartup="${tokenx.messaging.seckill-listener-enabled:true}",containerFactory="seckillKafkaListenerContainerFactory")
  public void consume(String payload) throws Exception {processor.process(json.readValue(payload,SeckillOrderCommand.class));}
}
