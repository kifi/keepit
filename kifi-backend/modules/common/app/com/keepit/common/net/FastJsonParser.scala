package com.keepit.common.net

import play.api.libs.json._
import com.keepit.common.strings.UTF8

object FastJsonParser {
  val emptyObjectBytes: Array[Byte] = "{}".getBytes(UTF8)
  val emptyObject: JsValue = Json.obj()

  val emptyArrayBytes: Array[Byte] = "[]".getBytes(UTF8)
  val emptyArray: JsValue = JsArray()
}

case class FastJsonParser() {
  def parse(bytes: Array[Byte]): JsValue = bytes match {
    case FastJsonParser.emptyObjectBytes => FastJsonParser.emptyObject
    case FastJsonParser.emptyArrayBytes => FastJsonParser.emptyArray
    case _ => Json.parse(bytes)
  }

}
