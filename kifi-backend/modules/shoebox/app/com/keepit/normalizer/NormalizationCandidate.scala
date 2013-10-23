package com.keepit.normalizer

import com.keepit.model.Normalization
import play.api.libs.json.JsObject

sealed trait NormalizationCandidate {
  val url: String
  val normalization: Normalization
  def isTrusted: Boolean
}

case class TrustedCandidate(url: String, normalization: Normalization) extends NormalizationCandidate {
  def isTrusted = true
}

case class UntrustedCandidate(url: String, normalization: Normalization) extends NormalizationCandidate {
  def isTrusted = false
}

object NormalizationCandidate {

  val acceptedSubmissions = Seq(Normalization.CANONICAL, Normalization.OPENGRAPH)

  def apply(json: JsObject): Seq[UntrustedCandidate] = {
    for {
      normalization <- acceptedSubmissions
      url <- (json \ normalization.scheme).asOpt[String]
    } yield UntrustedCandidate(url, normalization)
  }
}
