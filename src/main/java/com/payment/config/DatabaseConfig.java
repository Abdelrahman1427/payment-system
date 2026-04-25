package com.payment.config;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;

public class DatabaseConfig {

  private static PgPool client;

  public static Future<Void> init(Vertx vertx, JsonObject dbConfig) {

    Promise<Void> promise = Promise.promise();

    try {
      PgConnectOptions connectOptions = new PgConnectOptions()
        .setHost(dbConfig.getString("host"))
        .setPort(dbConfig.getInteger("port", 5432))
        .setDatabase(dbConfig.getString("database"))
        .setUser(dbConfig.getString("user"))
        .setPassword(dbConfig.getString("password"));

      PoolOptions poolOptions = new PoolOptions()
        .setMaxSize(dbConfig.getInteger("maxPoolSize", 10));

      client = PgPool.pool(vertx, connectOptions, poolOptions);

      // test connection
      client.query("SELECT 1").execute(ar -> {
        if (ar.succeeded()) {
          System.out.println(" Database connected successfully");
          promise.complete();
        } else {
          System.err.println(" Database connection failed");
          promise.fail(ar.cause());
        }
      });

    } catch (Exception e) {
      promise.fail(e);
    }

    return promise.future();
  }

  public static PgPool getClient() {
    if (client == null) {
      throw new IllegalStateException("Database not initialized. Call DatabaseConfig.init() first.");
    }
    return client;
  }
}
