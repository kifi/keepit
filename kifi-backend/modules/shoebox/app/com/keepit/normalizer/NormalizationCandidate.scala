package com.keepit.normalizer

import com.keepit.model.Normalization
import play.api.libs.json.JsObject

case class NormalizationCandidate(url: String, normalization: Normalization)

object NormalizationCandidate {
  def apply(json: JsObject, normalization: Normalization) : Option[NormalizationCandidate] =
    for (url <- (json \ normalization.tag).asOpt[String]) yield NormalizationCandidate(url, normalization)

  def apply(json: JsObject): Seq[NormalizationCandidate] = {
    Normalization.priority.keys.map(normalization => apply(json, normalization)).flatten.toSeq
  }
}
