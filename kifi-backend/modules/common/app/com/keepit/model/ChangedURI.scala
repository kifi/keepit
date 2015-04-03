package com.keepit.model
import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime

case class ChangedURI(
    id: Option[Id[ChangedURI]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    oldUriId: Id[NormalizedURI],
    newUriId: Id[NormalizedURI],
    state: State[ChangedURI] = ChangedURIStates.ACTIVE,
    seq: SequenceNumber[ChangedURI] = SequenceNumber.ZERO) extends ModelWithState[ChangedURI] with ModelWithSeqNumber[ChangedURI] {
  def withId(id: Id[ChangedURI]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[ChangedURI]) = copy(state = state)
}

object ChangedURIStates extends States[ChangedURI] {
  val APPLIED = State[ChangedURI]("applied") // Indicates we actually merged these uris
  val FAILED = State[ChangedURI]("failed")
}
