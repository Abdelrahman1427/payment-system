package com.payment.utils;

import io.vertx.core.json.JsonObject;

public class ResponseUtil {

  public static String success(JsonObject data) {
    return new JsonObject()
      .put("success", true)
      .put("data", data)
      .encode();
  }

  public static String success(JsonArray data) {
    return new JsonObject()
      .put("success", true)
      .put("data", data)
      .encode();
  }

  public static String error(String message) {
    return new JsonObject()
      .put("success", false)
      .put("error", new JsonObject()
        .put("message", message)
        .put("code", "ERROR"))
      .encode();
  }

  public static String error(String message, String code) {
    return new JsonObject()
      .put("success", false)
      .put("error", new JsonObject()
        .put("message", message)
        .put("code", code))
      .encode();
  }
}
