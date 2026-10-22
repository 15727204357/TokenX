package com.tokenx.messaging.service;

import com.tokenx.messaging.config.MessagingProperties;
import com.tokenx.messaging.model.SeckillOrderCommand;
import com.tokenx.order.mapper.SeckillRequestMapper;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.order.support.IdempotencyKeys;
import com.tokenx.order.service.SeckillAdmissionGate;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Records a stable terminal failure after a non-transient malformed command reaches the DLT. */
@Component
@ConditionalOnExpression("'${tokenx.messaging.kafka-enabled:true}' == 'true' && '${tokenx.seckill.mode:SYNC}' == 'ASYNC'")
public class SeckillOrderCommandsDeadLetterListener {
  private static final Logger log=LoggerFactory.getLogger(SeckillOrderCommandsDeadLetterListener.class);
  private final ObjectMapper json; private final SeckillRequestMapper requests; private final Clock clock; private final SeckillAdmissionGate gate;
  public SeckillOrderCommandsDeadLetterListener(ObjectMapper json,SeckillRequestMapper requests,Clock clock,SeckillAdmissionGate gate){this.json=json;this.requests=requests;this.clock=clock;this.gate=gate;}
  @KafkaListener(topics="${tokenx.messaging.seckill-orders-dlt-topic:tokenx.seckill-order-commands.dlt.v1}",groupId="${tokenx.messaging.seckill-consumer-group-id:tokenx-seckill-orders-v1}-dlt",autoStartup="${tokenx.messaging.seckill-listener-enabled:true}",containerFactory="seckillKafkaDltListenerContainerFactory")
  public void consume(String payload){
    final SeckillOrderCommand command;
    try {command=json.readValue(payload,SeckillOrderCommand.class);}
    catch(Exception ex){log.error("seckill command reached DLT and could not be decoded type={}",ex.getClass().getSimpleName());return;}
    if(command.requestId()!=null&&command.activityId()>0&&command.userId()>0){
      requests.finish(IdempotencyKeys.toBytes(command.requestId()),4,null,"COMMAND_UNPROCESSABLE",PurchaseOrder.toColumn(clock.instant()));
      try {gate.compensate(command.activityId(),command.userId(),command.requestId());}
      catch(org.springframework.dao.DataAccessException ignored){/* durable request state is authoritative */}
    }
  }
}
