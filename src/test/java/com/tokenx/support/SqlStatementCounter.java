package com.tokenx.support;

import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;

/**
 * Counts statements MyBatis actually sends to the database.
 *
 * <p>Used to prove that authenticated requests are served without touching {@code app_user}. It is
 * registered as a normal {@code Interceptor} bean, which MyBatis-Plus auto-configuration attaches to
 * the {@code SqlSessionFactory} of that test context.
 */
@Intercepts({
  @Signature(
      type = StatementHandler.class,
      method = "prepare",
      args = {Connection.class, Integer.class})
})
public class SqlStatementCounter implements Interceptor {

  private final AtomicInteger statements = new AtomicInteger();

  @Override
  public Object intercept(Invocation invocation) throws Throwable {
    statements.incrementAndGet();
    return invocation.proceed();
  }

  public int count() {
    return statements.get();
  }

  public void reset() {
    statements.set(0);
  }
}
