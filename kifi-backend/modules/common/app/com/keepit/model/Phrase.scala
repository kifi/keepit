package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.search.Lang

case class Phrase (
  id: Option[Id[Phrase]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  phrase: String,
  lang: Lang,
  source: String,
  state: State[Phrase] = PhraseStates.ACTIVE
  ) extends Model[Phrase] {
  def withId(id: Id[Phrase]): Phrase = copy(id = Some(id))
  def withUpdateTime(now: DateTime): Phrase = this.copy(updatedAt = now)
  def isActive: Boolean = state == PhraseStates.ACTIVE
  def withState(state: State[Phrase]): Phrase = copy(state = state)
}

object PhraseStates extends States[Phrase]
