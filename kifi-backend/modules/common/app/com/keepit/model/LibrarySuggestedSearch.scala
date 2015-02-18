package com.keepit.model

import com.keepit.common.db.{ States, ModelWithState, State, Id }
import org.joda.time.DateTime
import com.keepit.common.time._

case class LibrarySuggestedSearch(
    id: Option[Id[LibrarySuggestedSearch]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    libraryId: Id[Library],
    term: String,
    weight: Float,
    state: State[LibrarySuggestedSearch] = LibrarySuggestedSearchStates.ACTIVE) extends ModelWithState[LibrarySuggestedSearch] {

  def withId(id: Id[LibrarySuggestedSearch]): LibrarySuggestedSearch = copy(id = Some(id))
  def withUpdateTime(now: DateTime): LibrarySuggestedSearch = copy(updatedAt = now)
  def activateWithWeight(weight: Float): LibrarySuggestedSearch = copy(weight = weight, state = LibrarySuggestedSearchStates.ACTIVE)
}

object LibrarySuggestedSearchStates extends States[LibrarySuggestedSearch]

case class SuggestedSearchTerms(terms: Map[String, Float]) {
  def normalized(): SuggestedSearchTerms = SuggestedSearchTerms.create(this.terms)
}

object SuggestedSearchTerms {
  private val MAX_TERM_LEN = 128
  def create(terms: Map[String, Float]): SuggestedSearchTerms = {
    val ts = terms.map { case (word, weight) => (word.trim.toLowerCase.take(MAX_TERM_LEN), weight) }
    SuggestedSearchTerms(ts)
  }
}
