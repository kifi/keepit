package com.keepit.model

import org.joda.time.DateTime
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.common.mail.{ ElectronicMailCategory, EmailAddress }

case class EmailOptOut(
    id: Option[Id[EmailOptOut]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    address: EmailAddress,
    category: ElectronicMailCategory,
    state: State[EmailOptOut] = EmailOptOutStates.ACTIVE) extends ModelWithState[EmailOptOut] {
  def withId(id: Id[EmailOptOut]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[EmailOptOut]) = copy(state = state)
}

object EmailOptOutStates extends States[EmailOptOut]

