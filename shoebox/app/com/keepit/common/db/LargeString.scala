package com.keepit.common.db

import play.api.libs.json.Format
import scala.collection.immutable.StringLike

case class LargeString(value: String) {
  override def toString = value
}

object LargeString {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  implicit def toStandardString(value: LargeString): String = value.value
  implicit def toLargeString(value: String): LargeString = LargeString(value)
  implicit val format = Format(__.read[String].map(LargeString(_)), new Writes[LargeString]{ def writes(o: LargeString) = JsString(o.value) })
}
