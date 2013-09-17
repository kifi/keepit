package com.keepit.model
import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime

case class RenormalizedURL(
  id: Id[RenormalizedURL],
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  urlId: Id[URL],
  newUriId: Id[NormalizedURI],
  state: State[RenormalizedURL],
  seq: SequenceNumber = SequenceNumber.ZERO
) extends Model[RenormalizedURL] {
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[RenormalizedURL]) = copy(state = state)
}

object RenormalizedURL extends States[RenormalizedURL]{
  val APPLIED = State[RenormalizedURL]("applied")
}
