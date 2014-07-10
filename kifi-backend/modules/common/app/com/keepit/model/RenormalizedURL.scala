package com.keepit.model
import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime

case class RenormalizedURL(
    id: Option[Id[RenormalizedURL]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    urlId: Id[URL],
    oldUriId: Id[NormalizedURI],
    newUriId: Id[NormalizedURI],
    state: State[RenormalizedURL] = RenormalizedURLStates.ACTIVE,
    seq: SequenceNumber[RenormalizedURL] = SequenceNumber.ZERO) extends ModelWithState[RenormalizedURL] with ModelWithSeqNumber[RenormalizedURL] {
  def withId(id: Id[RenormalizedURL]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[RenormalizedURL]) = copy(state = state)
}

object RenormalizedURLStates extends States[RenormalizedURL] {
  val APPLIED = State[RenormalizedURL]("applied")
}
