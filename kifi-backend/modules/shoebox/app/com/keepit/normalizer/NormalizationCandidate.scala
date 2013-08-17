package com.keepit.normalizer

import com.keepit.model.Normalization
import play.api.libs.json.JsObject

case class NormalizationCandidate(url: String, normalization: Normalization)

object NormalizationCandidate {

  val acceptedSubmissions = Seq(Normalization.CANONICAL, Normalization.OPENGRAPH)

  def apply(json: JsObject): Seq[NormalizationCandidate] = {
    for {
      normalization <- acceptedSubmissions
      url <- (json \ normalization.scheme).asOpt[String]
    } yield NormalizationCandidate(url, normalization)
  }
}
