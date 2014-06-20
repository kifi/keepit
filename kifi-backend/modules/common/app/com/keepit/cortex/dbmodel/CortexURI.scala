package com.keepit.cortex.dbmodel

import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.common.db.State
import com.keepit.common.db.SequenceNumber
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.model.IndexableUri
import com.keepit.common.db.ModelWithSeqNumber
import com.keepit.common.db.ModelWithState
import org.joda.time.DateTime
import com.keepit.common.time._


case class CortexURI(
  id: Option[Id[CortexURI]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  uriId: Id[NormalizedURI],
  title: Option[String] = None,
  url: String,
  state: State[CortexURI],
  seq: SequenceNumber[CortexURI]
) extends ModelWithState[CortexURI] with ModelWithSeqNumber[CortexURI] {
  def withId(id: Id[CortexURI]): CortexURI = copy(id = Some(id))
  def withUpdateTime(now: DateTime): CortexURI = copy(updatedAt = now)
}

object CortexURI {
  implicit def fromURIState(state: State[NormalizedURI]): State[CortexURI] = State[CortexURI](state.value)
  implicit def fromURISeq(seq: SequenceNumber[NormalizedURI]): SequenceNumber[CortexURI] = SequenceNumber[CortexURI](seq.value)

  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[CortexURI]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'url).format[String] and
    (__ \ 'state).format(State.format[CortexURI]) and
    (__ \ 'seq).format(SequenceNumber.format[CortexURI])
  )(CortexURI.apply, unlift(CortexURI.unapply))

  def fromURI(uri: NormalizedURI): CortexURI = CortexURI(uriId = uri.id.get, title = uri.title, url = uri.url, state = uri.state, seq = uri.seq)
}
