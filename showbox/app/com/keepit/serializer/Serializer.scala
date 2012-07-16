package com.keepit.serializer

import play.api.libs.json.Writes
import play.api.libs.json.Reads
import play.api.libs.json.DefaultWrites
import play.api.libs.json.DefaultReads
import play.api.libs.json.JsNull
import play.api.libs.json.JsBoolean
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsValue


trait Serializer[E] extends Writes[E] with Reads[E] with DefaultWrites with DefaultReads {
    
  protected def asValue(value: String, display: Option[String] = None): JsValue = {
    val json = List("value" -> JsString(value))
    display match {
      case Some(disp) => JsObject(("display" -> JsString(disp)) :: json)
      case None => JsObject(json)
    }
  }
  
  protected def maybeValue(value: Option[String]): JsValue = value.map(asValue(_)).getOrElse(JsNull)
  
  
}
