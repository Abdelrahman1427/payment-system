package com.payment.models;

import io.vertx.core.json.JsonObject;
import java.time.Instant;

public class Payment {
  private String id;
  private String userId;
  private String moyasarId;
  private double amount;
  private String currency;
  private String status; // initiated, paid, failed, refunded
  private String description;
  private String callbackUrl;
  private String sourceType; // credit_card, applepay, stcpay
  private JsonObject source;
  private Instant createdAt;
  private Instant updatedAt;
  private String idempotencyKey;
  private int retryCount;

  // Constructors
  public Payment() {}

  public Payment(JsonObject json) {
    this.id = json.getString("id");
    this.userId = json.getString("user_id");
    this.moyasarId = json.getString("moyasar_id");
    this.amount = json.getDouble("amount");
    this.currency = json.getString("currency", "SAR");
    this.status = json.getString("status", "initiated");
    this.description = json.getString("description");
    this.callbackUrl = json.getString("callback_url");
    this.sourceType = json.getString("source_type");
    this.source = json.getJsonObject("source");
    this.createdAt = json.getInstant("created_at");
    this.updatedAt = json.getInstant("updated_at");
    this.idempotencyKey = json.getString("idempotency_key");
  }

  // Getters and Setters
  public String getId() { return id; }
  public void setId(String id) { this.id = id; }

  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }

  public String getMoyasarId() { return moyasarId; }
  public void setMoyasarId(String moyasarId) { this.moyasarId = moyasarId; }

  public double getAmount() { return amount; }
  public void setAmount(double amount) { this.amount = amount; }

  public String getCurrency() { return currency; }
  public void setCurrency(String currency) { this.currency = currency; }

  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }

  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }

  public String getCallbackUrl() { return callbackUrl; }
  public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }

  public String getSourceType() { return sourceType; }
  public void setSourceType(String sourceType) { this.sourceType = sourceType; }

  public JsonObject getSource() { return source; }
  public void setSource(JsonObject source) { this.source = source; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

  public String getIdempotencyKey() { return idempotencyKey; }
  public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

  public int getRetryCount() { return retryCount; }
  public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

  public JsonObject toJson() {
    return new JsonObject()
      .put("id", id)
      .put("user_id", userId)
      .put("moyasar_id", moyasarId)
      .put("amount", amount)
      .put("currency", currency)
      .put("status", status)
      .put("description", description)
      .put("callback_url", callbackUrl)
      .put("source_type", sourceType)
      .put("source", source)
      .put("created_at", createdAt)
      .put("updated_at", updatedAt)
      .put("idempotency_key", idempotencyKey);
  }
}
