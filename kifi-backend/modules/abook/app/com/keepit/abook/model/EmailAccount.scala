package com.keepit.abook.model

import com.keepit.common.db._
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.model.User
import org.joda.time.DateTime

case class EmailAccount(
    id: Option[Id[EmailAccount]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[EmailAccount] = EmailAccountStates.ACTIVE,
    address: EmailAddress,
    userId: Option[Id[User]] = None,
    verified: Boolean = false,
    seq: SequenceNumber[EmailAccount] = SequenceNumber.ZERO) extends ModelWithState[EmailAccount] with ModelWithSeqNumber[EmailAccount] {

  def withId(id: Id[EmailAccount]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[EmailAccount]) = copy(state = state)

  if (verified) { require(userId.isDefined, "Verified EmailAccount doesn't belong to any user.") }
}

object EmailAccount {
  implicit def toEmailAccountInfoId(id: Id[EmailAccount]): Id[EmailAccountInfo] = id.copy()
  implicit def fromEmailAccountInfoId(id: Id[EmailAccountInfo]): Id[EmailAccount] = id.copy()
  implicit def toEmailAccountInfoSeq(seq: SequenceNumber[EmailAccount]): SequenceNumber[EmailAccountInfo] = seq.copy()
  def toInfo(emailAccount: EmailAccount): EmailAccountInfo = {
    EmailAccountInfo(emailAccount.id.get, address = emailAccount.address, userId = emailAccount.userId, verified = emailAccount.verified, seq = emailAccount.seq)
  }
}

object EmailAccountStates extends States[EmailAccount]
