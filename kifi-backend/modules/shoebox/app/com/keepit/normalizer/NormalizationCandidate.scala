package com.keepit.normalizer

import com.keepit.model.Normalization
import play.api.libs.json.JsObject

sealed trait NormalizationCandidate {
  val url: String
  val normalization: Normalization
}

case class TrustedCandidate(url: String, normalization: Normalization) extends NormalizationCandidate
case class UntrustedCandidate(url: String, normalization: Normalization) extends NormalizationCandidate

object NormalizationCandidate {

  val acceptedSubmissions = Seq(Normalization.CANONICAL, Normalization.OPENGRAPH)

  def apply(json: JsObject): Seq[UntrustedCandidate] = {
    for {
      normalization <- acceptedSubmissions
      url <- (json \ normalization.scheme).asOpt[String]
    } yield UntrustedCandidate(url, normalization)
  }
}
