package com.tokenx.order.service;

import com.tokenx.activity.mapper.ActivityInventoryMapper;
import com.tokenx.infrastructure.error.BusinessException;
import com.tokenx.order.mapper.SeckillRequestMapper;
import com.tokenx.order.support.IdempotencyKeys;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Reconstructs a paused gate from MySQL inventory and durable nonterminal requests. */
@Service
public class SeckillAdmissionRecoveryService {
  private final SeckillAdmissionGate gate; private final ActivityInventoryMapper inventory; private final SeckillRequestMapper requests;
  public SeckillAdmissionRecoveryService(SeckillAdmissionGate gate,ActivityInventoryMapper inventory,SeckillRequestMapper requests){this.gate=gate;this.inventory=inventory;this.requests=requests;}
  public void rebuild(long activity){
    gate.markRebuilding(activity);
    if(gate.inflight(activity)>0)throw new BusinessException(HttpStatus.CONFLICT,"ADMISSION_REBUILD_IN_PROGRESS","unconfirmed Kafka sends are still settling; retry after reconciliation");
    if(requests.countUnknownByActivity(activity)>0)throw new BusinessException(HttpStatus.CONFLICT,"ADMISSION_REBUILD_UNKNOWN_REQUESTS","Kafka delivery is ambiguous; reconcile these request IDs against broker offsets before rebuilding");
    var stock=inventory.selectById(activity);
    if(stock==null)throw new BusinessException(HttpStatus.NOT_FOUND,"ACTIVITY_NOT_FOUND","activity not found");
    var pending=requests.findPendingByActivity(activity);
    long remaining=(long)stock.getAvailableStock()-pending.size();
    if(remaining<0)throw new BusinessException(HttpStatus.CONFLICT,"ADMISSION_REBUILD_MISMATCH","pending requests exceed MySQL available stock");
    gate.resetForRebuild(activity,remaining);
    for(var row:pending)gate.restorePending(activity,row.getUserId(),IdempotencyKeys.fromBytes(row.getRequestId()));
    gate.open(activity,remaining);
  }
}
