package com.keepit.classify

import org.joda.time.DateTime
import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.time._

case class DomainToTag(
    id: Option[Id[DomainToTag]] = None,
    domainId: Id[Domain],
    tagId: Id[DomainTag],
    state: State[DomainToTag] = DomainToTagStates.ACTIVE,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime) extends ModelWithState[DomainToTag] {
  def withId(id: Id[DomainToTag]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[DomainToTag]) = this.copy(state = state)
}

object DomainToTagStates extends States[DomainToTag]
