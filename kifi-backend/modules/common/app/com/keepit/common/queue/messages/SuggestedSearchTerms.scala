package com.keepit.common.queue.messages

import com.keepit.common.db.Id
import com.keepit.model.Library
import com.kifi.macros.json
import play.api.libs.json.Json

case class SuggestedSearchTerms(terms: Map[String, Float]) {
  def normalized(): SuggestedSearchTerms = SuggestedSearchTerms.create(this.terms)
  def takeTopK(k: Int): SuggestedSearchTerms = {
    SuggestedSearchTerms(terms.toArray.sortBy(-_._2).take(k).toMap)
  }
}

object SuggestedSearchTerms {
  val MAX_CACHE_LIMIT = 100 // If this goes up, better bump up cache version
  private val MAX_TERM_LEN = 128

  def create(terms: Map[String, Float]): SuggestedSearchTerms = {
    val ts = terms.map { case (word, weight) => (word.trim.toLowerCase.take(MAX_TERM_LEN), weight) }
    SuggestedSearchTerms(ts)
  }

  implicit def format = Json.format[SuggestedSearchTerms]
}

@json case class SuggestedSearchTermsWithLibraryId(libId: Id[Library], terms: SuggestedSearchTerms)
