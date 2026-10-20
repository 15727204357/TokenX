package com.tokenx.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * Row of the F0-2 compatibility probe table.
 *
 * <p>This entity exists only to prove the MySQL / Flyway / MyBatis-Plus wiring. It is not part of
 * the business model and disappears with the migration that introduces the first real table.
 */
@TableName("schema_probe")
public class SchemaProbe {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String probeKey;

  private String note;

  private LocalDateTime createdAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getProbeKey() {
    return probeKey;
  }

  public void setProbeKey(String probeKey) {
    this.probeKey = probeKey;
  }

  public String getNote() {
    return note;
  }

  public void setNote(String note) {
    this.note = note;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
