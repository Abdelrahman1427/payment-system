package com.payment.verticles;

import com.payment.config.DatabaseConfig;
import com.payment.models.Payment;
import com.payment.utils.CircuitBreakerFactory;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.time.Instant;
import java.util.UUID;

public class PaymentVerticle extends AbstractVerticle {

  private WebClient webClient;
  private PgPool pgPool;
  private CircuitBreaker moyasarBreaker;
  private JsonObject moyasarConfig;

  @Override
  public void start(Promise<Void> startPromise) {
    this.moyasarConfig = config().getJsonObject("moyasar");

    // Web Client للتكامل مع Moyasar
    webClient = WebClient.create(vertx);

    // Circuit Breaker لحماية من تعطل Moyasar
    moyasarBreaker = CircuitBreakerFactory.create(vertx, "moyasar",
      config().getJsonObject("circuitBreaker"));

    // الاتصال بـ PostgreSQL
    pgPool = DatabaseConfig.getClient();

    // تسجيل الـ Event Bus Consumers
    vertx.eventBus().consumer("payment.create", this::createPayment);
    vertx.eventBus().consumer("payment.get", this::getPayment);
    vertx.eventBus().consumer("payment.list", this::listPayments);
    vertx.eventBus().consumer("payment.refund", this::refundPayment);
    vertx.eventBus().consumer("payment.status", this::getStatus);
    vertx.eventBus().consumer("payment.updateStatus", this::updateStatus);

    System.out.println("💳 Payment Service started");
    startPromise.complete();
  }

  // ========== CREATE PAYMENT ==========
  private void createPayment(Message<JsonObject> message) {
    JsonObject request = message.body();

    // التحقق من البيانات
    if (!validatePaymentRequest(request)) {
      message.fail(400, "Invalid payment request");
      return;
    }

    String idempotencyKey = request.getString("idempotency_key");

    // التحقق من Idempotency
    checkIdempotency(idempotencyKey)
      .compose(existing -> {
        if (existing != null) {
          // دفع موجود بنفس المفتاح
          return Future.succeededFuture(existing);
        }

        // إنشاء دفع جديد
        return createNewPayment(request);
      })
      .onSuccess(payment -> message.reply(payment.toJson()))
      .onFailure(err -> message.fail(500, err.getMessage()));
  }

  private Future<Payment> createNewPayment(JsonObject request) {
    Promise<Payment> promise = Promise.promise();

    Payment payment = new Payment();
    payment.setId(UUID.randomUUID().toString());
    payment.setUserId(request.getString("user_id"));
    payment.setAmount(request.getDouble("amount"));
    payment.setCurrency(request.getString("currency", "SAR"));
    payment.setDescription(request.getString("description"));
    payment.setCallbackUrl(request.getString("callback_url"));
    payment.setSourceType(request.getString("source_type", "credit_card"));
    payment.setSource(request.getJsonObject("source"));
    payment.setStatus("initiated");
    payment.setCreatedAt(Instant.now());
    payment.setIdempotencyKey(request.getString("idempotency_key"));

    // حفظ في DB أولاً
    savePayment(payment)
      .compose(v -> {
        // إرسال لـ Moyasar
        return callMoyasar(payment);
      })
      .compose(moyasarResponse -> {
        // تحديث بيانات Moyasar
        payment.setMoyasarId(moyasarResponse.getString("id"));
        payment.setStatus(moyasarResponse.getString("status"));

        if (moyasarResponse.containsKey("source")) {
          payment.setSource(moyasarResponse.getJsonObject("source"));
        }

        return updatePaymentWithMoyasarData(payment);
      })
      .onSuccess(v -> promise.complete(payment))
      .onFailure(promise::fail);

    return promise.future();
  }

  private Future<JsonObject> callMoyasar(Payment payment) {
    return moyasarBreaker.execute(promise -> {
      JsonObject moyasarRequest = new JsonObject()
        .put("amount", (int)(payment.getAmount() * 100)) // Moyasar يتعامل بالهللة
        .put("currency", payment.getCurrency())
        .put("description", payment.getDescription())
        .put("callback_url", payment.getCallbackUrl())
        .put("source", payment.getSource());

      webClient.postAbs(moyasarConfig.getString("baseUrl") + "/payments")
        .basicAuthentication(moyasarConfig.getString("secretKey"), "")
        .putHeader("Content-Type", "application/json")
        .sendJsonObject(moyasarRequest, ar -> {
          if (ar.succeeded()) {
            HttpResponse<Buffer> response = ar.result();

            if (response.statusCode() == 201 || response.statusCode() == 200) {
              promise.complete(response.bodyAsJsonObject());
            } else {
              promise.fail("Moyasar error: " + response.bodyAsString());
            }
          } else {
            promise.fail(ar.cause());
          }
        });
    });
  }

  // ========== GET PAYMENT ==========
  private void getPayment(Message<JsonObject> message) {
    String paymentId = message.body().getString("id");

    pgPool.preparedQuery("SELECT * FROM payments WHERE id = $1")
      .execute(Tuple.of(paymentId), ar -> {
        if (ar.succeeded()) {
          RowSet<Row> rows = ar.result();
          if (rows.iterator().hasNext()) {
            Row row = rows.iterator().next();
            message.reply(rowToPayment(row).toJson());
          } else {
            message.fail(404, "Payment not found");
          }
        } else {
          message.fail(500, ar.cause().getMessage());
        }
      });
  }

  // ========== LIST PAYMENTS ==========
  private void listPayments(Message<JsonObject> message) {
    JsonObject query = message.body();
    String userId = query.getString("user_id");
    int page = Integer.parseInt(query.getString("page", "1"));
    int limit = Integer.parseInt(query.getString("limit", "20"));
    int offset = (page - 1) * limit;

    pgPool.preparedQuery(
        "SELECT * FROM payments WHERE user_id = $1 ORDER BY created_at DESC LIMIT $2 OFFSET $3")
      .execute(Tuple.of(userId, limit, offset), ar -> {
        if (ar.succeeded()) {
          JsonArray payments = new JsonArray();
          for (Row row : ar.result()) {
            payments.add(rowToPayment(row).toJson());
          }

          message.reply(new JsonObject()
            .put("payments", payments)
            .put("page", page)
            .put("limit", limit));
        } else {
          message.fail(500, ar.cause().getMessage());
        }
      });
  }

  // ========== REFUND ==========
  private void refundPayment(Message<JsonObject> message) {
    JsonObject request = message.body();
    String paymentId = request.getString("payment_id");
    double amount = request.getDouble("amount");

    // جلب بيانات الدفع
    pgPool.preparedQuery("SELECT * FROM payments WHERE id = $1 AND status = 'paid'")
      .execute(Tuple.of(paymentId), ar -> {
        if (ar.succeeded() && ar.result().iterator().hasNext()) {
          Row row = ar.result().iterator().next();
          String moyasarId = row.getString("moyasar_id");

          // إرسال refund لـ Moyasar
          refundInMoyasar(moyasarId, amount)
            .compose(v -> updatePaymentStatus(paymentId, "refunded"))
            .onSuccess(v -> message.reply(new JsonObject()
              .put("status", "refunded")
              .put("amount", amount)))
            .onFailure(err -> message.fail(500, err.getMessage()));
        } else {
          message.fail(404, "Payment not found or not paid");
        }
      });
  }

  private Future<Void> refundInMoyasar(String moyasarId, double amount) {
    Promise<Void> promise = Promise.promise();

    JsonObject refundRequest = new JsonObject();
    if (amount > 0) {
      refundRequest.put("amount", (int)(amount * 100));
    }

    webClient.postAbs(moyasarConfig.getString("baseUrl") + "/payments/" + moyasarId + "/refund")
      .basicAuthentication(moyasarConfig.getString("secretKey"), "")
      .sendJsonObject(refundRequest, ar -> {
        if (ar.succeeded() && ar.result().statusCode() == 200) {
          promise.complete();
        } else {
          promise.fail("Refund failed: " +
            (ar.failed() ? ar.cause().getMessage() : ar.result().bodyAsString()));
        }
      });

    return promise.future();
  }

  // ========== UPDATE STATUS (من Webhook) ==========
  private void updateStatus(Message<JsonObject> message) {
    JsonObject update = message.body();
    String paymentId = update.getString("payment_id");
    String status = update.getString("status");

    updatePaymentStatus(paymentId, status)
      .onSuccess(v -> message.reply(new JsonObject().put("updated", true)))
      .onFailure(err -> message.fail(500, err.getMessage()));
  }

  private void getStatus(Message<JsonObject> message) {
    String paymentId = message.body().getString("id");

    pgPool.preparedQuery("SELECT status, moyasar_id FROM payments WHERE id = $1")
      .execute(Tuple.of(paymentId), ar -> {
        if (ar.succeeded() && ar.result().iterator().hasNext()) {
          Row row = ar.result().iterator().next();
          message.reply(new JsonObject()
            .put("status", row.getString("status"))
            .put("moyasar_id", row.getString("moyasar_id")));
        } else {
          message.fail(404, "Payment not found");
        }
      });
  }

  // ========== DATABASE HELPERS ==========

  private Future<Void> savePayment(Payment payment) {
    Promise<Void> promise = Promise.promise();

    pgPool.preparedQuery(
        "INSERT INTO payments (id, user_id, amount, currency, status, description, " +
          "callback_url, source_type, source, created_at, idempotency_key) " +
          "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)")
      .execute(Tuple.of(
        payment.getId(),
        payment.getUserId(),
        payment.getAmount(),
        payment.getCurrency(),
        payment.getStatus(),
        payment.getDescription(),
        payment.getCallbackUrl(),
        payment.getSourceType(),
        payment.getSource() != null ? payment.getSource().encode() : null,
        payment.getCreatedAt(),
        payment.getIdempotencyKey()
      ), ar -> {
        if (ar.succeeded()) promise.complete();
        else promise.fail(ar.cause());
      });

    return promise.future();
  }

  private Future<Void> updatePaymentWithMoyasarData(Payment payment) {
    Promise<Void> promise = Promise.promise();

    pgPool.preparedQuery(
        "UPDATE payments SET moyasar_id = $1, status = $2, source = $3, updated_at = $4 WHERE id = $5")
      .execute(Tuple.of(
        payment.getMoyasarId(),
        payment.getStatus(),
        payment.getSource() != null ? payment.getSource().encode() : null,
        Instant.now(),
        payment.getId()
      ), ar -> {
        if (ar.succeeded()) promise.complete();
        else promise.fail(ar.cause());
      });

    return promise.future();
  }

  private Future<Void> updatePaymentStatus(String paymentId, String status) {
    Promise<Void> promise = Promise.promise();

    pgPool.preparedQuery(
        "UPDATE payments SET status = $1, updated_at = $2 WHERE id = $3")
      .execute(Tuple.of(status, Instant.now(), paymentId), ar -> {
        if (ar.succeeded()) promise.complete();
        else promise.fail(ar.cause());
      });

    return promise.future();
  }

  private Future<Payment> checkIdempotency(String key) {
    Promise<Payment> promise = Promise.promise();

    if (key == null) {
      promise.complete(null);
      return promise.future();
    }

    pgPool.preparedQuery("SELECT * FROM payments WHERE idempotency_key = $1")
      .execute(Tuple.of(key), ar -> {
        if (ar.succeeded() && ar.result().iterator().hasNext()) {
          promise.complete(rowToPayment(ar.result().iterator().next()));
        } else {
          promise.complete(null);
        }
      });

    return promise.future();
  }

  private Payment rowToPayment(Row row) {
    Payment payment = new Payment();
    payment.setId(row.getString("id"));
    payment.setUserId(row.getString("user_id"));
    payment.setMoyasarId(row.getString("moyasar_id"));
    payment.setAmount(row.getDouble("amount"));
    payment.setCurrency(row.getString("currency"));
    payment.setStatus(row.getString("status"));
    payment.setDescription(row.getString("description"));
    payment.setCallbackUrl(row.getString("callback_url"));
    payment.setSourceType(row.getString("source_type"));

    String sourceStr = row.getString("source");
    if (sourceStr != null) {
      payment.setSource(new JsonObject(sourceStr));
    }

    payment.setCreatedAt(row.getOffsetDateTime("created_at").toInstant());
    payment.setIdempotencyKey(row.getString("idempotency_key"));
    return payment;
  }

  private boolean validatePaymentRequest(JsonObject request) {
    return request.containsKey("amount")
      && request.getDouble("amount") > 0
      && request.containsKey("source");
  }
}
