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

    // تحميل الإعدادات
    ConfigStoreOptions fileStore = new ConfigStoreOptions()
      .setType("file")
      .setConfig(new JsonObject().put("path", "config.json"));

    ConfigRetriever retriever = ConfigRetriever.create(vertx,
      new ConfigRetrieverOptions().addStore(fileStore));

    retriever.getConfig(ar -> {
      if (ar.succeeded()) {
        JsonObject config = ar.result();
        deployVerticles(vertx, config);
      } else {
        System.err.println("Failed to load config: " + ar.cause().getMessage());
      }
    });
  }

  private static void deployVerticles(Vertx vertx, JsonObject config) {
    // نشر كل الـ Verticles

    // 1. قاعدة البيانات أولاً
    DatabaseConfig.init(vertx, config.getJsonObject("database"))
      .compose(v -> {
        Promise<String> promise = Promise.promise();

        // 2. API Gateway
        vertx.deployVerticle(new ApiGatewayVerticle(config),
          new DeploymentOptions().setInstances(2), gateway -> {

            if (gateway.succeeded()) {
              System.out.println("✅ API Gateway deployed: " + gateway.result());

              // 3. Payment Service
              vertx.deployVerticle(new PaymentVerticle(config), payment -> {
                if (payment.succeeded()) {
                  System.out.println("✅ Payment Service deployed");

                  // 4. Webhook Service
                  vertx.deployVerticle(new WebhookVerticle(config), webhook -> {
                    if (webhook.succeeded()) {
                      System.out.println("✅ Webhook Service deployed");

                      // 5. Notification Service
                      vertx.deployVerticle(new NotificationVerticle(config), notif -> {
                        if (notif.succeeded()) {
                          System.out.println("✅ Notification Service deployed");
                          promise.complete();
                        } else {
                          promise.fail(notif.cause());
                        }
                      });
                    } else {
                      promise.fail(webhook.cause());
                    }
                  });
                } else {
                  promise.fail(payment.cause());
                }
              });
            } else {
              promise.fail(gateway.cause());
            }
          });

        return promise.future();
      })
      .onSuccess(v -> System.out.println("🚀 System Ready!"))
      .onFailure(err -> {
        System.err.println("❌ Failed to start: " + err.getMessage());
        vertx.close();
      });
  }
}
