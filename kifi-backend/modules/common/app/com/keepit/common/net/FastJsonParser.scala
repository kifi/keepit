package com.keepit.common.net

import play.api.libs.json._

import com.keepit.common.strings.UTF8
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError, StackTrace}

object FastJsonParser {
  val emptyObjectString: String = "{}"
  val emptyObject: JsValue = Json.obj()

  val emptyArrayString: String = "[]"
  val emptyArray: JsValue = JsArray()

  val nullString: String = "null"
  val nullVal: JsValue = JsNull
}

case class JsonParserTrackingErrorMessage(message: String) extends AnyVal

class FastJsonParser() {
  def parse(bytes: Array[Byte], alertThreshold: Int = 100): (JsValue, Long, Option[JsonParserTrackingErrorMessage]) = {
    val startTime = System.currentTimeMillis
    val json = fastParse(bytes)
    val jsonTime = System.currentTimeMillis - startTime

    val tracking = if (jsonTime > alertThreshold) {//ms
        val message: String = if (bytes.size <= 4) {
          new String(bytes, UTF8) match {
            case FastJsonParser.emptyObjectString => "matching empty object bytes"
            case FastJsonParser.emptyArrayString => "matching empty array bytes"
            case FastJsonParser.nullString => "matching null bytes"
            case _ => "not matching pattern"
          }
        } else {
          "long message, no attempt to match"
        }
        Some(JsonParserTrackingErrorMessage(message))
      } else {
        None
      }

    val notNull = if (json == null) JsNull else json
    (notNull, jsonTime, tracking)
  }

  //scala pattern matching on array is very slow using strings on small arrays
  private def fastParse(bytes: Array[Byte]) = if (bytes.size <= 4) { //4 == "null".getBytes(UTF8).size
    new String(bytes, UTF8) match {
      case FastJsonParser.emptyObjectString => FastJsonParser.emptyObject
      case FastJsonParser.emptyArrayString => FastJsonParser.emptyArray
      case FastJsonParser.nullString => FastJsonParser.nullVal
      case other => Json.parse(other)
    }
  } else {
    Json.parse(bytes)
  }
}
