package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.search.Lang

import play.api.libs.json._
import play.api.libs.functional.syntax._

case class Phrase(
    id: Option[Id[Phrase]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    phrase: String,
    lang: Lang,
    source: String,
    state: State[Phrase] = PhraseStates.ACTIVE,
    seq: SequenceNumber[Phrase] = SequenceNumber.ZERO) extends ModelWithState[Phrase] with ModelWithSeqNumber[Phrase] {
  def withId(id: Id[Phrase]): Phrase = copy(id = Some(id))
  def withUpdateTime(now: DateTime): Phrase = this.copy(updatedAt = now)
  def isActive: Boolean = state == PhraseStates.ACTIVE
  def withState(state: State[Phrase]): Phrase = copy(state = state)
}

object Phrase {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[Phrase]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'phrase).format[String] and
    (__ \ 'lang).format[String].inmap(Lang.apply, unlift(Lang.unapply)) and
    (__ \ 'source).format[String] and
    (__ \ 'state).format(State.format[Phrase]) and
    (__ \ 'seq).format(SequenceNumber.format[Phrase])
  )(Phrase.apply, unlift(Phrase.unapply))
}

object PhraseStates extends States[Phrase]
