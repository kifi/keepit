package com.keepit.payments

import com.keepit.common.db.{ States, ModelWithState, Id, State }
import com.keepit.common.time._
import com.keepit.model.{ Organization, User }
import com.keepit.common.mail.EmailAddress

import org.joda.time.DateTime

case class PaidAccount(
    id: Option[Id[PaidAccount]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[PaidAccount] = PaidAccountStates.ACTIVE,
    orgId: Id[Organization],
    planId: Id[PaidPlan],
    credit: Int, //in cents
    userContacts: Seq[Id[User]],
    emailContacts: Seq[EmailAddress]) extends ModelWithState[PaidAccount] {

  def withId(id: Id[PaidAccount]): PaidAccount = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): PaidAccount = this.copy(updatedAt = now)
}

object PaidAccountStates extends States[PaidAccount]
