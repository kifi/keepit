package com.keepit.model

import play.api.libs.json._

case class Normalization(tag: String)

object Normalization extends Ordering[Normalization] {

  implicit def format: Format[Normalization] = Format(
    __.read[String].map(Normalization(_)),
    new Writes[Normalization]{ def writes(o: Normalization) = JsString(o.tag) }
  )

  def compare(a: Normalization, b: Normalization) = priority(a).compare(priority(b))

  val CANONICAL = Normalization("canonical")
  val OPENGRAPH = Normalization("og")
  val HTTPS = Normalization("https://")
  val HTTPSWWW = Normalization("https://www")
  val HTTP = Normalization("http://")
  val HTTPWWW = Normalization("http://www")
  val HTTPSM = Normalization("https://m")
  val HTTPM = Normalization("http://m")

  lazy val priority = Map[Normalization, Int](
    CANONICAL -> 1,
    OPENGRAPH -> 2,
    HTTPS -> 3,
    HTTPSWWW -> 4,
    HTTP -> 5,
    HTTPWWW -> 6,
    HTTPSM -> 7,
    HTTPM -> 8
  )
}
