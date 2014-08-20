package com.keepit.curator.model

import com.keepit.common.db.{ ModelWithSeqNumber, Model, SequenceNumber, States, State, Id }
import com.keepit.common.time.currentDateTime
import com.keepit.model.NormalizedURI
import org.joda.time.DateTime
import com.keepit.common.time._

case class PublicFeed(
    id: Option[Id[PublicFeed]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[PublicFeed] = PublicFeedStates.ACTIVE,
    seq: SequenceNumber[PublicFeed] = SequenceNumber.ZERO,
    uriId: Id[NormalizedURI],
    publicMasterScore: Float,
    publicAllScores: PublicUriScores) extends Model[PublicFeed] with ModelWithSeqNumber[PublicFeed] {
  def withId(id: Id[PublicFeed]): PublicFeed = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): PublicFeed = this.copy(updatedAt = updateTime)
}

object PublicFeedStates extends States[PublicFeed]
