package com.payment.verticles;

import com.payment.security.JwtAuthProvider;
import com.payment.utils.ResponseUtil;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.*;
import io.vertx.ext.web.sstore.LocalSessionStore;

public class ApiGatewayVerticle extends AbstractVerticle {

  private JWTAuth jwtAuth;
  private JsonObject config;

  @Override
  public void start(Promise<Void> startPromise) {
    this.config = config();

    // إعداد JWT
    jwtAuth = JwtAuthProvider.create(vertx, config.getJsonObject("jwt"));

    Router router = Router.router(vertx);

    // Middlewares
    setupMiddlewares(router);

    // Routes
    setupRoutes(router);

    // Error Handler
    router.errorHandler(500, ctx -> {
      ctx.response()
        .setStatusCode(500)
        .end(ResponseUtil.error("Internal Server Error"));
    });

    // 404 Handler
    router.route().last().handler(ctx -> {
      ctx.response()
        .setStatusCode(404)
        .end(ResponseUtil.error("Not Found"));
    });

    vertx.createHttpServer()
      .requestHandler(router)
      .listen(config.getJsonObject("http").getInteger("port", 8080))
      .onSuccess(server -> {
        System.out.println("🌐 API Gateway listening on port " + server.actualPort());
        startPromise.complete();
      })
      .onFailure(startPromise::fail);
  }

  private void setupMiddlewares(Router router) {
    // Body Parser
    router.route().handler(BodyHandler.create());

    // CORS
    router.route().handler(CorsHandler.create()
      .addOrigin("*")
      .allowedMethod(HttpMethod.GET)
      .allowedMethod(HttpMethod.POST)
      .allowedMethod(HttpMethod.PUT)
      .allowedMethod(HttpMethod.DELETE)
      .allowedHeader("Content-Type")
      .allowedHeader("Authorization")
      .allowedHeader("Idempotency-Key"));

    // Session (للـ 3DS)
    router.route().handler(SessionHandler.create(LocalSessionStore.create(vertx)));

    // Request Logger
    router.route().handler(ctx -> {
      System.out.println("📥 " + ctx.request().method() + " " + ctx.request().path());
      ctx.next();
    });
  }

  private void setupRoutes(Router router) {
    // Health Check
    router.get("/health").handler(ctx -> {
      ctx.response().end(ResponseUtil.success(new JsonObject()
        .put("status", "UP")
        .put("timestamp", System.currentTimeMillis())));
    });

    // Public Routes
    router.post("/auth/login").handler(this::login);
    router.post("/webhooks/moyasar").handler(this::handleWebhook);

    // Protected Routes
    router.route("/api/*").handler(JWTAuthHandler.create(jwtAuth));

    // Payments
    router.post("/api/payments").handler(this::createPayment);
    router.get("/api/payments/:id").handler(this::getPayment);
    router.get("/api/payments").handler(this::listPayments);
    router.post("/api/payments/:id/refund").handler(this::refundPayment);
    router.get("/api/payments/:id/status").handler(this::getPaymentStatus);

    // Wallet
    router.get("/api/wallet/balance").handler(this::getWalletBalance);
    router.get("/api/wallet/transactions").handler(this::getWalletTransactions);
  }

  // ========== HANDLERS ==========

  private void login(RoutingContext ctx) {
    JsonObject body = ctx.getBodyAsJson();
    String apiKey = body.getString("api_key");
    String apiSecret = body.getString("api_secret");

    // التحقق من API Key (في الواقع من DB)
    if ("valid_key".equals(apiKey) && "valid_secret".equals(apiSecret)) {
      String token = jwtAuth.generateToken(new JsonObject()
        .put("sub", "merchant_123")
        .put("role", "merchant"));

      ctx.response().end(ResponseUtil.success(new JsonObject()
        .put("token", token)
        .put("expires_in", 3600)));
    } else {
      ctx.response()
        .setStatusCode(401)
        .end(ResponseUtil.error("Invalid credentials"));
    }
  }

  private void createPayment(RoutingContext ctx) {
    JsonObject paymentRequest = ctx.getBodyAsJson();

    // إضافة معلومات المستخدم من JWT
    paymentRequest.put("user_id", ctx.user().principal().getString("sub"));

    // التحقق من Idempotency Key
    String idempotencyKey = ctx.request().getHeader("Idempotency-Key");
    if (idempotencyKey != null) {
      paymentRequest.put("idempotency_key", idempotencyKey);
    }

    // إرسال للـ Payment Service
    vertx.eventBus().request("payment.create", paymentRequest, reply -> {
      if (reply.succeeded()) {
        ctx.response().end(ResponseUtil.success(reply.result().body()));
      } else {
        ctx.response()
          .setStatusCode(400)
          .end(ResponseUtil.error(reply.cause().getMessage()));
      }
    });
  }

  private void getPayment(RoutingContext ctx) {
    String paymentId = ctx.pathParam("id");

    vertx.eventBus().request("payment.get",
      new JsonObject().put("id", paymentId), reply -> {

        if (reply.succeeded()) {
          ctx.response().end(ResponseUtil.success(reply.result().body()));
        } else {
          ctx.response()
            .setStatusCode(404)
            .end(ResponseUtil.error("Payment not found"));
        }
      });
  }

  private void listPayments(RoutingContext ctx) {
    JsonObject query = new JsonObject()
      .put("user_id", ctx.user().principal().getString("sub"))
      .put("page", ctx.request().getParam("page", "1"))
      .put("limit", ctx.request().getParam("limit", "20"));

    vertx.eventBus().request("payment.list", query, reply -> {
      if (reply.succeeded()) {
        ctx.response().end(ResponseUtil.success(reply.result().body()));
      } else {
        ctx.response()
          .setStatusCode(500)
          .end(ResponseUtil.error(reply.cause().getMessage()));
      }
    });
  }

  private void refundPayment(RoutingContext ctx) {
    String paymentId = ctx.pathParam("id");
    JsonObject refundRequest = ctx.getBodyAsJson();
    refundRequest.put("payment_id", paymentId);

    vertx.eventBus().request("payment.refund", refundRequest, reply -> {
      if (reply.succeeded()) {
        ctx.response().end(ResponseUtil.success(reply.result().body()));
      } else {
        ctx.response()
          .setStatusCode(400)
          .end(ResponseUtil.error(reply.cause().getMessage()));
      }
    });
  }

  private void getPaymentStatus(RoutingContext ctx) {
    String paymentId = ctx.pathParam("id");

    vertx.eventBus().request("payment.status",
      new JsonObject().put("id", paymentId), reply -> {

        if (reply.succeeded()) {
          ctx.response().end(ResponseUtil.success(reply.result().body()));
        } else {
          ctx.response()
            .setStatusCode(404)
            .end(ResponseUtil.error("Payment not found"));
        }
      });
  }

  private void getWalletBalance(RoutingContext ctx) {
    String userId = ctx.user().principal().getString("sub");

    vertx.eventBus().request("wallet.balance",
      new JsonObject().put("user_id", userId), reply -> {

        if (reply.succeeded()) {
          ctx.response().end(ResponseUtil.success(reply.result().body()));
        } else {
          ctx.response()
            .setStatusCode(500)
            .end(ResponseUtil.error(reply.cause().getMessage()));
        }
      });
  }

  private void getWalletTransactions(RoutingContext ctx) {
    String userId = ctx.user().principal().getString("sub");
    JsonObject query = new JsonObject()
      .put("user_id", userId)
      .put("page", ctx.request().getParam("page", "1"))
      .put("limit", ctx.request().getParam("limit", "20"));

    vertx.eventBus().request("wallet.transactions", query, reply -> {
      if (reply.succeeded()) {
        ctx.response().end(ResponseUtil.success(reply.result().body()));
      } else {
        ctx.response()
          .setStatusCode(500)
          .end(ResponseUtil.error(reply.cause().getMessage()));
      }
    });
  }

  private void handleWebhook(RoutingContext ctx) {
    JsonObject webhookData = ctx.getBodyAsJson();
    String signature = ctx.request().getHeader("X-Moyasar-Signature");

    webhookData.put("signature", signature);

    // إرسال للـ Webhook Service
    vertx.eventBus().send("webhook.moyasar", webhookData);

    // رد فوري (200 OK) - Moyasar يحتاج رد سريع
    ctx.response().end(ResponseUtil.success(new JsonObject()
      .put("received", true)));
  }
}
