package com.keepit.normalizer

import com.keepit.model.Normalization
import play.api.libs.json.JsObject

case class NormalizationCandidate(url: String, normalization: Normalization) {
  require(url == url.toLowerCase, "Normalized urls must be all lowercase.")
}

object NormalizationCandidate {

  val acceptedSubmissions = Seq(Normalization.CANONICAL, Normalization.OPENGRAPH)

  def apply(json: JsObject): Seq[NormalizationCandidate] = {
    for {
      normalization <- acceptedSubmissions
      url <- (json \ normalization.scheme).asOpt[String]
    } yield NormalizationCandidate(url.toLowerCase, normalization)
  }
}
