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
    state: State[CortexURI],
    seq: SequenceNumber[CortexURI],
    shouldHaveContent: Boolean = false) extends ModelWithState[CortexURI] with ModelWithSeqNumber[CortexURI] {
  def withId(id: Id[CortexURI]): CortexURI = copy(id = Some(id))
  def withUpdateTime(now: DateTime): CortexURI = copy(updatedAt = now)
}

object CortexURI {
  implicit def fromURIState(state: State[NormalizedURI]): State[CortexURI] = State[CortexURI](state.value)
  implicit def fromURISeq(seq: SequenceNumber[NormalizedURI]): SequenceNumber[CortexURI] = SequenceNumber[CortexURI](seq.value)

  def fromURI(uri: NormalizedURI): CortexURI = CortexURI(uriId = uri.id.get, state = uri.state, seq = uri.seq, shouldHaveContent = uri.shouldHaveContent)
}
