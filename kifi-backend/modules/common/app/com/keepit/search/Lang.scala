package com.keepit.search

case class Lang(lang: String) extends AnyVal

object Lang {
  import play.api.libs.json._

  implicit def format = Format(__.read[String].map(Lang(_)), new Writes[Lang] { def writes(o: Lang): JsValue = JsString(o.lang) })
}