package com.keepit.model

import play.api.libs.json._

case class Restriction(context: String)

object Restriction {
  implicit def format: Format[Restriction] = Format(
    __.read[String].map(Restriction(_)),
    new Writes[Restriction] { def writes(o: Restriction) = JsString(o.context) }
  )

  def http(statusCode: Int): Restriction = Restriction(s"HTTP ${statusCode}")
  val http = """^HTTP (\d{3})$""".r
  val redirects = Seq(301, 302, 303, 307).map(http(_))
  val ADULT = Restriction("adult")
}
