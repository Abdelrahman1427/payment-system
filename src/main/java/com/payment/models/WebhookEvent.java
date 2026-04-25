package com.payment.models;

import io.vertx.core.json.JsonObject;
import java.time.Instant;

public class WebhookEvent {
  private String id;
  private String type; // payment.paid, payment.failed, payment.refunded
  private String moyasarId;
  private JsonObject payload;
  private boolean processed;
  private Instant receivedAt;
  private String signature;

  public WebhookEvent() {}

  public WebhookEvent(JsonObject json) {
    this.id = json.getString("id");
    this.type = json.getString("type");
    this.moyasarId = json.getString("moyasar_id");
    this.payload = json.getJsonObject("payload");
    this.processed = json.getBoolean("processed", false);
    this.receivedAt = json.getInstant("received_at");
    this.signature = json.getString("signature");
  }

  // Getters and Setters
  public String getId() { return id; }
  public void setId(String id) { this.id = id; }

  public String getType() { return type; }
  public void setType(String type) { this.type = type; }

  public String getMoyasarId() { return moyasarId; }
  public void setMoyasarId(String moyasarId) { this.moyasarId = moyasarId; }

  public JsonObject getPayload() { return payload; }
  public void setPayload(JsonObject payload) { this.payload = payload; }

  public boolean isProcessed() { return processed; }
  public void setProcessed(boolean processed) { this.processed = processed; }

  public Instant getReceivedAt() { return receivedAt; }
  public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }

  public String getSignature() { return signature; }
  public void setSignature(String signature) { this.signature = signature; }
}
