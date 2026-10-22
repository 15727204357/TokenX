package com.tokenx.order.service;

import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** O(1) Lua admission and idempotent compensation. Redis loss fails closed. */
@Component
public class SeckillAdmissionGate {
  private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
      local state=redis.call('HGET',KEYS[1],'state')
      if state ~= 'OPEN' or redis.call('EXISTS',KEYS[2]) == 0 or redis.call('EXISTS',KEYS[3]) == 0 then
        redis.call('HSET',KEYS[1],'state','REBUILDING')
        return -1
      end
      local old=redis.call('HGET',KEYS[3],ARGV[1])
      if old then
        local who,st=string.match(old,'([^|]+)|([^|]+)')
        if who ~= ARGV[2] then return -2 end
        if st == 'COMPENSATED' then
          local prior=redis.call('HGET',KEYS[2],ARGV[2])
          if prior then return -2 end
          local n=tonumber(redis.call('HGET',KEYS[1],'remaining') or '-1')
          if n < 0 then return -1 end
          if n == 0 then return 0 end
          redis.call('HSET',KEYS[1],'remaining',n-1)
          redis.call('HINCRBY',KEYS[1],'inflight',1)
          redis.call('HSET',KEYS[2],ARGV[2],ARGV[1])
          redis.call('HSET',KEYS[3],ARGV[1],ARGV[2]..'|RESERVED')
          return 1
        end
        return 2
      end
      local prior=redis.call('HGET',KEYS[2],ARGV[2])
      if prior then return -2 end
      local n=tonumber(redis.call('HGET',KEYS[1],'remaining') or '-1')
      if n < 0 then return -1 end
      if n == 0 then return 0 end
      redis.call('HSET',KEYS[1],'remaining',n-1)
      redis.call('HINCRBY',KEYS[1],'inflight',1)
      redis.call('HSET',KEYS[2],ARGV[2],ARGV[1])
      redis.call('HSET',KEYS[3],ARGV[1],ARGV[2]..'|RESERVED')
      return 1
      """, Long.class);
  private static final DefaultRedisScript<Long> QUEUED = new DefaultRedisScript<>("""
      local old=redis.call('HGET',KEYS[3],ARGV[1])
      if not old then return 0 end
      local who,st=string.match(old,'([^|]+)|([^|]+)')
      if who ~= ARGV[2] then return 0 end
      if st == 'QUEUED' then return 1 end
      if st == 'UNKNOWN' then
        redis.call('HSET',KEYS[3],ARGV[1],ARGV[2]..'|QUEUED')
        return 1
      end
      if st ~= 'RESERVED' then return 0 end
      redis.call('HSET',KEYS[3],ARGV[1],ARGV[2]..'|QUEUED')
      if tonumber(redis.call('HGET',KEYS[1],'inflight') or '0') > 0 then redis.call('HINCRBY',KEYS[1],'inflight',-1) end
      return 1
      """, Long.class);
  private static final DefaultRedisScript<Long> UNKNOWN = new DefaultRedisScript<>("local old=redis.call('HGET',KEYS[3],ARGV[1]); if not old then return 0 end; local who,st=string.match(old,'([^|]+)|([^|]+)'); if who ~= ARGV[2] then return 0 end; if st == 'RESERVED' then redis.call('HSET',KEYS[3],ARGV[1],ARGV[2]..'|UNKNOWN'); if tonumber(redis.call('HGET',KEYS[1],'inflight') or '0') > 0 then redis.call('HINCRBY',KEYS[1],'inflight',-1) end; return 1 end; if st == 'UNKNOWN' or st == 'QUEUED' then return 1 end; return 0",Long.class);
  private static final DefaultRedisScript<Long> COMPENSATE = new DefaultRedisScript<>("""
      local old=redis.call('HGET',KEYS[3],ARGV[1])
      if not old then return 1 end
      local who,st=string.match(old,'([^|]+)|([^|]+)')
      if who ~= ARGV[2] then return 0 end
      if st == 'COMPENSATED' then return 1 end
      if st ~= 'RESERVED' and st ~= 'QUEUED' then return 0 end
      redis.call('HINCRBY',KEYS[1],'remaining',1)
      if st == 'RESERVED' then redis.call('HINCRBY',KEYS[1],'inflight',-1) end
      redis.call('HDEL',KEYS[2],ARGV[2])
      redis.call('HSET',KEYS[3],ARGV[1],ARGV[2]..'|COMPENSATED')
      return 1
      """, Long.class);
  private static final DefaultRedisScript<Long> MARK_REBUILDING = new DefaultRedisScript<>("redis.call('HSET',KEYS[1],'state','REBUILDING'); return 1",Long.class);
  private static final DefaultRedisScript<Long> OPEN = new DefaultRedisScript<>("redis.call('HSET',KEYS[1],'state','OPEN','remaining',ARGV[1]); return 1",Long.class);
  private static final DefaultRedisScript<Long> INITIALIZE = new DefaultRedisScript<>("if redis.call('HEXISTS',KEYS[1],'state') == 1 then return 0 end; redis.call('HSET',KEYS[1],'state','OPEN','remaining',ARGV[1],'inflight','0'); redis.call('HSET',KEYS[2],'__ready__','1'); redis.call('HSET',KEYS[3],'__ready__','1'); for i=1,3 do redis.call('EXPIRE',KEYS[i],ARGV[2]) end; return 1",Long.class);
  private static final DefaultRedisScript<String> STATUS = new DefaultRedisScript<>("local v=redis.call('HGET',KEYS[3],ARGV[1]); if not v then return '' end; local who,st=string.match(v,'([^|]+)|([^|]+)'); if who ~= ARGV[2] then return '' end; return st",String.class);
  private static final DefaultRedisScript<Long> INFLIGHT = new DefaultRedisScript<>("return tonumber(redis.call('HGET',KEYS[1],'inflight') or '0')",Long.class);
  private static final DefaultRedisScript<Long> RESTORE = new DefaultRedisScript<>("redis.call('HSET',KEYS[2],ARGV[1],ARGV[2]); redis.call('HSET',KEYS[3],ARGV[2],ARGV[1]..'|QUEUED'); return 1",Long.class);
  private static final DefaultRedisScript<Long> RESET = new DefaultRedisScript<>("redis.call('DEL',KEYS[2],KEYS[3]); redis.call('HSET',KEYS[1],'state','REBUILDING','remaining',ARGV[1],'inflight','0'); redis.call('HSET',KEYS[2],'__ready__','1'); redis.call('HSET',KEYS[3],'__ready__','1'); return 1",Long.class);
  private final StringRedisTemplate redis;
  public SeckillAdmissionGate(StringRedisTemplate redis){this.redis=redis;}
  private List<String> keys(long activity){String tag="tokenx:seckill:{"+activity+"}";return List.of(tag+":gate",tag+":users",tag+":requests");}
  public long reserve(long activity,long user,UUID request){return run(RESERVE,activity,user,request);}
  public boolean queued(long activity,long user,UUID request){return Long.valueOf(1).equals(redis.execute(QUEUED,keys(activity),request.toString(),Long.toString(user)));}
  public void unknown(long activity,long user,UUID request){redis.execute(UNKNOWN,keys(activity),request.toString(),Long.toString(user));}
  public void compensate(long activity,long user,UUID request){redis.execute(COMPENSATE,keys(activity),request.toString(),Long.toString(user));}
  public void markRebuilding(long activity){redis.execute(MARK_REBUILDING,keys(activity));}
  public void open(long activity,long remaining){redis.execute(OPEN,keys(activity),Long.toString(remaining));}
  public void initialize(long activity,long remaining,long ttlSeconds){redis.execute(INITIALIZE,keys(activity),Long.toString(remaining),Long.toString(ttlSeconds));}
  public String status(long activity,long user,UUID request){return redis.execute(STATUS,keys(activity),request.toString(),Long.toString(user));}
  public long inflight(long activity){Long n=redis.execute(INFLIGHT,keys(activity));return n==null?0:n;}
  public void restorePending(long activity,long user,UUID request){redis.execute(RESTORE,keys(activity),Long.toString(user),request.toString());}
  public void resetForRebuild(long activity,long remaining){redis.execute(RESET,keys(activity),Long.toString(remaining));}
  private long run(DefaultRedisScript<Long> script,long activity,long user,UUID request){Long n=redis.execute(script,keys(activity),request.toString(),Long.toString(user));return n==null?-1:n;}
}
