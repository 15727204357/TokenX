package com.tokenx.order.service;

import com.tokenx.activity.mapper.ActivityInventoryMapper;
import com.tokenx.activity.service.ActivityService;
import com.tokenx.catalog.service.CatalogService;
import com.tokenx.infrastructure.error.BusinessException;
import com.tokenx.order.mapper.PurchaseOrderMapper;
import com.tokenx.order.mapper.SeckillRequestMapper;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.order.model.SeckillRequestRow;
import com.tokenx.order.support.IdempotencyKeys;
import com.tokenx.messaging.model.SeckillOrderCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Commits request outcome and order effects together; listener returns only after this commits. */
@Service
public class SeckillOrderCommandProcessor {
  private final SeckillRequestMapper requests;
  private final PurchaseOrderMapper orders;
  private final ActivityInventoryMapper inventory;
  private final ActivityService activities;
  private final CatalogService catalog;
  private final OrderDraft drafts;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final SeckillAdmissionGate gate;
  public SeckillOrderCommandProcessor(SeckillRequestMapper requests,PurchaseOrderMapper orders,ActivityInventoryMapper inventory,ActivityService activities,CatalogService catalog,OrderDraft drafts,Clock clock,PlatformTransactionManager tm,SeckillAdmissionGate gate){this.requests=requests;this.orders=orders;this.inventory=inventory;this.activities=activities;this.catalog=catalog;this.drafts=drafts;this.clock=clock;this.tx=new TransactionTemplate(tm);this.gate=gate;}
  public void process(SeckillOrderCommand command){
    if(command.schemaVersion()!=1||command.requestId()==null||command.activityId()<=0||command.userId()<=0)throw new IllegalArgumentException("invalid seckill order command");
    byte[] requestId=IdempotencyKeys.toBytes(command.requestId());
    requests.markProcessing(requestId,PurchaseOrder.toColumn(clock.instant()));
    try { tx.executeWithoutResult(s -> {
      SeckillRequestRow row=requests.findByRequestId(requestId);
      if(row!=null && row.getStatus()>=2 && row.getStatus()<=4)return;
      if(row==null){row=new SeckillRequestRow();row.setRequestId(requestId);row.setUserId(command.userId());row.setActivityId(command.activityId());row.setStatus(0);row.setCreatedAt(PurchaseOrder.toColumn(clock.instant()));row.setUpdatedAt(row.getCreatedAt());requests.insert(row);}
      Instant now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
      try {
        PurchaseOrder existing=orders.selectByUserAndRequest(command.userId(),requestId);
        if(existing!=null){requests.finish(requestId,2,existing.getId(),null,PurchaseOrder.toColumn(now));return;}
        var snapshot=activities.requireOrderable(command.activityId(),now);
        PurchaseOrder order=drafts.buildSeckill(command.userId(),requestId,snapshot,catalog.packageNameFor(snapshot.packageId()),now);
        if(inventory.reserveOne(command.activityId())!=1){requests.finish(requestId,3,null,"ACTIVITY_SOLD_OUT",PurchaseOrder.toColumn(now));return;}
        orders.insert(order);
        requests.finish(requestId,2,order.getId(),null,PurchaseOrder.toColumn(now));
      } catch(BusinessException ex){
        requests.finish(requestId,3,null,ex.getCode(),PurchaseOrder.toColumn(now));
      }
    }); } catch(DuplicateKeyException duplicate){
      tx.executeWithoutResult(s->{PurchaseOrder winner=orders.selectByActivityAndUser(command.activityId(),command.userId());if(winner==null)throw duplicate;requests.finish(IdempotencyKeys.toBytes(command.requestId()),3,null,"ALREADY_PURCHASED",PurchaseOrder.toColumn(clock.instant()));});
    }
    byte[] key=IdempotencyKeys.toBytes(command.requestId());
    SeckillRequestRow outcome=requests.findByRequestId(key);
    try {
      if(outcome!=null&&outcome.getStatus()==3)gate.compensate(command.activityId(),command.userId(),command.requestId());
      else gate.queued(command.activityId(),command.userId(),command.requestId());
    } catch (org.springframework.dao.DataAccessException ignored) { /* MySQL is authoritative; the gate can be rebuilt from it. */ }
  }
}
