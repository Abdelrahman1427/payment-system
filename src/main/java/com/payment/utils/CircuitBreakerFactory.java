package com.payment.utils;

import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

public class CircuitBreakerFactory {

  public static CircuitBreaker create(Vertx vertx, String name, JsonObject config) {
    return CircuitBreaker.create(name, vertx,
      new CircuitBreakerOptions()
        .setMaxFailures(config.getInteger("maxFailures", 5))
        .setTimeout(config.getLong("timeout", 5000L))
        .setResetTimeout(config.getLong("resetTimeout", 30000L))
        .setFallbackOnFailure(true));
  }
}
