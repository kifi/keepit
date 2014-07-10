package com.keepit.model

import play.api.libs.json._

case class Normalization(scheme: String) extends Ordered[Normalization] {
  def priority = Normalization.priority(this)
  def compare(that: Normalization) = -this.priority.compare(that.priority)
}

object Normalization {

  implicit def format: Format[Normalization] = Format(
    __.read[String].map(Normalization(_)),
    new Writes[Normalization] { def writes(o: Normalization) = JsString(o.scheme) }
  )

  val CANONICAL = Normalization("canonical")
  val OPENGRAPH = Normalization("og")
  val HTTPS = Normalization("https://")
  val HTTPSWWW = Normalization("https://www")
  val HTTP = Normalization("http://")
  val HTTPWWW = Normalization("http://www")
  val HTTPSM = Normalization("https://m")
  val HTTPSMOBILE = Normalization("https://mobile")
  val HTTPM = Normalization("http://m")
  val HTTPMOBILE = Normalization("http://mobile")
  val MOVED = Normalization("301")

  lazy val priority = Map[Normalization, Int](
    CANONICAL -> 0,
    OPENGRAPH -> 1,
    HTTPS -> 2,
    HTTPSWWW -> 3,
    HTTP -> 4,
    HTTPWWW -> 5,
    HTTPSM -> 6,
    HTTPSMOBILE -> 6,
    HTTPM -> 7,
    HTTPMOBILE -> 7,
    MOVED -> Int.MaxValue
  )

  lazy val schemes = Set(HTTPMOBILE, HTTPM, HTTPSMOBILE, HTTPSM, HTTPWWW, HTTP, HTTPSWWW, HTTPS)
}
