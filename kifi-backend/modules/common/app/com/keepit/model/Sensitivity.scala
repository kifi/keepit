package com.keepit.model

import play.api.libs.json._

case class Sensitivity(info: String)

object Sensitivity {
  implicit def format: Format[Sensitivity] = Format(
    __.read[String].map(Sensitivity(_)),
    new Writes[Sensitivity]{ def writes(o: Sensitivity) = JsString(o.info) }
  )
}
