package com.keepit.cortex.dbmodel

import org.joda.time.DateTime
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model._
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class CortexKeep(
    id: Option[Id[CortexKeep]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    keptAt: DateTime,
    keepId: Id[Keep],
    userId: Option[Id[User]],
    uriId: Id[NormalizedURI],
    state: State[CortexKeep],
    source: KeepSource,
    seq: SequenceNumber[CortexKeep],
    libraryId: Option[Id[Library]] = None) extends ModelWithState[CortexKeep] with ModelWithSeqNumber[CortexKeep] {
  def withId(id: Id[CortexKeep]): CortexKeep = copy(id = Some(id))
  def withUpdateTime(now: DateTime): CortexKeep = copy(updatedAt = now)
}

object CortexKeep {
  implicit def fromKeepState(state: State[Keep]): State[CortexKeep] = State[CortexKeep](state.value)
  implicit def fromKeepSeq(seq: SequenceNumber[Keep]): SequenceNumber[CortexKeep] = SequenceNumber[CortexKeep](seq.value)
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[CortexKeep]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'keptAt).format(DateTimeJsonFormat) and
    (__ \ 'keepId).format(Id.format[Keep]) and
    (__ \ 'userId).formatNullable[Id[User]] and
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'state).format(State.format[CortexKeep]) and
    (__ \ 'source).format(Json.format[KeepSource]) and
    (__ \ 'seq).format(SequenceNumber.format[CortexKeep]) and
    (__ \ 'libraryId).formatNullable[Id[Library]]
  )(CortexKeep.apply, unlift(CortexKeep.unapply))

  def fromKeep(keep: Keep): CortexKeep =
    CortexKeep(
      keptAt = keep.createdAt,
      keepId = keep.id.get,
      userId = keep.userId,
      uriId = keep.uriId,
      state = keep.state,
      source = keep.source,
      seq = keep.seq,
      libraryId = keep.lowestLibraryId
    )
}
