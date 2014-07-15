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
  implicit def toIngestableEmailAccountId(id: Id[EmailAccount]): Id[IngestableEmailAccount] = id.copy()
  implicit def toIngestableEmailAccountSeq(seq: SequenceNumber[EmailAccount]): SequenceNumber[IngestableEmailAccount] = seq.copy()
  def toIngestable(emailAccount: EmailAccount): IngestableEmailAccount = {
    IngestableEmailAccount(emailAccount.id.get, userId = emailAccount.userId, verified = emailAccount.verified, seq = emailAccount.seq)
  }
}

object EmailAccountStates extends States[EmailAccount]
