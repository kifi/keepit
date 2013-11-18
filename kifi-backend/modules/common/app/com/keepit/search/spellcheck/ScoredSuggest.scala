package com.keepit.search.spellcheck

import play.api.libs.json._
import play.api.libs.functional.syntax._

case class Suggest(value: String)
case class ScoredSuggest(value: String, score: Float)

object ScoredSuggest {
  implicit val scoredSuggestFormat = Json.format[ScoredSuggest]
}