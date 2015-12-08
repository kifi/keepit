package com.keepit.common.queue.messages

import java.text.Normalizer

import com.keepit.common.db.Id
import com.keepit.model.Library
import com.kifi.macros.json
import play.api.libs.json.Json

case class SuggestedSearchTerms(terms: Map[String, Float]) {
  def takeTopK(k: Int): SuggestedSearchTerms = {
    SuggestedSearchTerms(terms.toArray.sortBy(-_._2).take(k).toMap)
  }
}

object SuggestedSearchTerms {
  val MAX_CACHE_LIMIT = 100 // If this goes up, better bump up cache version
  private val MAX_TERM_LEN = 128

  private val diacriticalMarksRegex = "\\p{InCombiningDiacriticalMarks}+".r
  def normalized(term: String): String = diacriticalMarksRegex.replaceAllIn(Normalizer.normalize(term.trim, Normalizer.Form.NFD), "").toLowerCase.take(MAX_TERM_LEN)
  def toBePersisted(term: String): String = term.trim.toLowerCase.take(MAX_TERM_LEN)

  implicit def format = Json.format[SuggestedSearchTerms]
}

@json case class SuggestedSearchTermsWithLibraryId(libId: Id[Library], terms: SuggestedSearchTerms)
