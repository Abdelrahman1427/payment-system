//package com.payment;
//
//import io.vertx.core.Future;
//import io.vertx.core.VerticleBase;
//
//public class MainVerticle extends VerticleBase {
//
//  @Override
//  public Future<?> start() {
//    return vertx.createHttpServer().requestHandler(req -> {
//      req.response()
//        .putHeader("content-type", "text/plain")
//        .end("Hello from Vert.x!");
//    }).listen(8888).onSuccess(http -> {
//      System.out.println("HTTP server started on port 8888");
//    });
//  }
//}
package com.payment;

import com.payment.config.DatabaseConfig;
import com.payment.verticles.*;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.*;
import io.vertx.core.json.JsonObject;

public class Main {

  public static void main(String[] args) {

    Vertx vertx = Vertx.vertx(new VertxOptions()
      .setEventLoopPoolSize(4)
      .setWorkerPoolSize(20)
    );

    // Load config.json
    ConfigStoreOptions fileStore = new ConfigStoreOptions()
      .setType("file")
      .setConfig(new JsonObject().put("path", "config.json"));

    ConfigRetriever retriever = ConfigRetriever.create(
      vertx,
      new ConfigRetrieverOptions().addStore(fileStore)
    );

    retriever.getConfig(ar -> {
      if (ar.succeeded()) {
        JsonObject config = ar.result();
        startApp(vertx, config);
      } else {
        System.err.println(" Failed to load config: " + ar.cause().getMessage());
        vertx.close();
      }
    });
  }

  private static void startApp(Vertx vertx, JsonObject config) {

    // 1. Init Database FIRST (must return Future<Void>)
    DatabaseConfig.init(vertx, config.getJsonObject("database"))
      .compose(v -> deployApiGateway(vertx, config))
      .compose(v -> deployPayment(vertx, config))
      .compose(v -> deployWebhook(vertx, config))
      .compose(v -> deployNotification(vertx, config))
      .onSuccess(v -> {
        System.out.println(" SYSTEM READY - ALL SERVICES DEPLOYED");
      })
      .onFailure(err -> {
        System.err.println(" Startup failed: " + err.getMessage());
        vertx.close();
      });
  }

  // ===== Deployment Steps =====

  private static Future<Void> deployApiGateway(Vertx vertx, JsonObject config) {
    Promise<Void> promise = Promise.promise();

    vertx.deployVerticle(
      new ApiGatewayVerticle(),
      new DeploymentOptions().setInstances(2),
      res -> {
        if (res.succeeded()) {
          System.out.println(" API Gateway deployed");
          promise.complete();
        } else {
          promise.fail(res.cause());
        }
      }
    );

    return promise.future();
  }

  private static Future<Void> deployPayment(Vertx vertx, JsonObject config) {
    Promise<Void> promise = Promise.promise();

    vertx.deployVerticle(new PaymentVerticle(), res -> {
      if (res.succeeded()) {
        System.out.println(" Payment Service deployed");
        promise.complete();
      } else {
        promise.fail(res.cause());
      }
    });

    return promise.future();
  }

  private static Future<Void> deployWebhook(Vertx vertx, JsonObject config) {
    Promise<Void> promise = Promise.promise();

    vertx.deployVerticle(new WebhookVerticle(), res -> {
      if (res.succeeded()) {
        System.out.println(" Webhook Service deployed");
        promise.complete();
      } else {
        promise.fail(res.cause());
      }
    });

    return promise.future();
  }

  private static Future<Void> deployNotification(Vertx vertx, JsonObject config) {
    Promise<Void> promise = Promise.promise();

    vertx.deployVerticle(new NotificationVerticle(), res -> {
      if (res.succeeded()) {
        System.out.println(" Notification Service deployed");
        promise.complete();
      } else {
        promise.fail(res.cause());
      }
    });

    return promise.future();
  }
}
