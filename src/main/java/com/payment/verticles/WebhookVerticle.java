package com.payment.verticles;

import com.payment.models.WebhookEvent;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Tuple;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public class WebhookVerticle extends AbstractVerticle {

  private PgPool pgPool;
  private String webhookSecret;

  @Override
  public void start(Promise<Void> startPromise) {
    this.webhookSecret = config().getJsonObject("moyasar").getString("webhookSecret");
    pgPool = PgPool.pool(vertx, config().getJsonObject("database"));

    vertx.eventBus().consumer("webhook.moyasar", this::handleMoyasarWebhook);

    System.out.println("🔔 Webhook Service started");
    startPromise.complete();
  }

  private void handleMoyasarWebhook(Message<JsonObject> message) {
    JsonObject payload = message.body();
    String signature = payload.getString("signature");

    // إزالة الـ signature من الـ payload قبل التحقق
    payload.remove("signature");

    // التحقق من التوقيع
    if (!verifySignature(payload.encode(), signature)) {
      System.err.println("❌ Invalid webhook signature");
      // حفظ في DB للمراجعة
      saveWebhookEvent(payload, signature, false);
      return;
    }

    // حفظ الـ Webhook
    saveWebhookEvent(payload, signature, true)
      .compose(v -> processWebhook(payload))
      .onSuccess(v -> System.out.println("✅ Webhook processed: " + payload.getString("id")))
      .onFailure(err -> System.err.println("❌ Webhook processing failed: " + err.getMessage()));
  }

  private Future<Void> processWebhook(JsonObject payload) {
    Promise<Void> promise = Promise.promise();

    String eventType = payload.getString("type");
    JsonObject data = payload.getJsonObject("data");
    String moyasarId = data.getString("id");
    String status = data.getString("status");

    // تحديث حالة الدفع
    vertx.eventBus().request("payment.updateStatus",
      new JsonObject()
        .put("payment_id", moyasarId)
        .put("status", status),
      ar -> {
        if (ar.succeeded()) {
          // إرسال إشعار
          vertx.eventBus().send("notification.send", new JsonObject()
            .put("type", "payment_status_update")
            .put("moyasar_id", moyasarId)
            .put("status", status));

          promise.complete();
        } else {
          promise.fail(ar.cause());
        }
      });

    return promise.future();
  }

  private boolean verifySignature(String payload, String signature) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      SecretKeySpec secretKeySpec = new SecretKeySpec(webhookSecret.getBytes(), "HmacSHA256");
      mac.init(secretKeySpec);
      byte[] hash = mac.doFinal(payload.getBytes());
      String expectedSignature = Base64.getEncoder().encodeToString(hash);
      return expectedSignature.equals(signature);
    } catch (Exception e) {
      return false;
    }
  }

  private Future<Void> saveWebhookEvent(JsonObject payload, String signature, boolean valid) {
    Promise<Void> promise = Promise.promise();

    WebhookEvent event = new WebhookEvent();
    event.setId(UUID.randomUUID().toString());
    event.setType(payload.getString("type"));
    event.setMoyasarId(payload.getJsonObject("data").getString("id"));
    event.setPayload(payload);
    event.setProcessed(valid);
    event.setReceivedAt(Instant.now());
    event.setSignature(signature);

    pgPool.preparedQuery(
        "INSERT INTO webhook_events (id, type, moyasar_id, payload, processed, received_at, signature) " +
          "VALUES ($1, $2, $3, $4, $5, $6, $7)")
      .execute(Tuple.of(
        event.getId(),
        event.getType(),
        event.getMoyasarId(),
        event.getPayload().encode(),
        event.isProcessed(),
        event.getReceivedAt(),
        event.getSignature()
      ), ar -> {
        if (ar.succeeded()) promise.complete();
        else promise.fail(ar.cause());
      });

    return promise.future();
  }
}
