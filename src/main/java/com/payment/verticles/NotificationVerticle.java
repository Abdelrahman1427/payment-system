package com.payment.verticles;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;

public class NotificationVerticle extends AbstractVerticle {

  @Override
  public void start(Promise<Void> startPromise) {
    vertx.eventBus().consumer("notification.send", this::sendNotification);

    System.out.println("📧 Notification Service started");
    startPromise.complete();
  }

  private void sendNotification(Message<JsonObject> message) {
    JsonObject notification = message.body();
    String type = notification.getString("type");

    switch (type) {
      case "payment_status_update":
        handlePaymentStatusUpdate(notification);
        break;
      case "refund_completed":
        handleRefundNotification(notification);
        break;
      default:
        System.out.println("Unknown notification type: " + type);
    }
  }

  private void handlePaymentStatusUpdate(JsonObject notification) {
    String moyasarId = notification.getString("moyasar_id");
    String status = notification.getString("status");

    // هنا تقدر تضيف:
    // 1. إرسال Email
    // 2. إرسال Push Notification
    // 3. إرسال SMS
    // 4. Webhook للتاجر

    System.out.println("📨 Payment " + moyasarId + " is now " + status);

    // مثال: إرسال للتاجر عبر Webhook
    String merchantWebhook = "https://merchant.com/webhooks/payments";
    // webClient.postAbs(merchantWebhook).sendJsonObject(notification);
  }

  private void handleRefundNotification(JsonObject notification) {
    System.out.println("💸 Refund notification: " + notification.encode());
  }
}
