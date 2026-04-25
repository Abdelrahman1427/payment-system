package com.payment.utils;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class ResponseUtil {

  public static String success(Object data) {

    JsonObject response = new JsonObject()
      .put("success", true);

    if (data instanceof JsonObject) {
      response.put("data", (JsonObject) data);
    } else if (data instanceof JsonArray) {
      response.put("data", (JsonArray) data);
    } else {
      response.put("data", data);
    }

    return response.encode();
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
