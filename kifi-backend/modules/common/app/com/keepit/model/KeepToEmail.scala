package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.mail.{ EmailAddressHash, EmailAddress }
import com.keepit.common.time._
import org.joda.time.DateTime

final case class KeepToEmail(
  id: Option[Id[KeepToEmail]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[KeepToEmail] = KeepToEmailStates.ACTIVE,
  keepId: Id[Keep],
  emailAddress: EmailAddress,
  addedAt: DateTime = currentDateTime,
  addedBy: Option[Id[User]],
  // Denormalized fields from Keep
  uriId: Id[NormalizedURI],
  lastActivityAt: DateTime)
    extends ModelWithState[KeepToEmail] {
  def emailAddressHash: EmailAddressHash = EmailAddressHash.hashEmailAddress(emailAddress)

  def withId(id: Id[KeepToEmail]): KeepToEmail = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): KeepToEmail = this.copy(updatedAt = now)
  def withState(newState: State[KeepToEmail]): KeepToEmail = this.copy(state = newState)
  def withUriId(newUriId: Id[NormalizedURI]) = this.copy(uriId = newUriId)
  def withAddedAt(time: DateTime) = this.copy(addedAt = time)

  // denormalized from Keep.lastActivityAt, use in KeepCommander.updateLastActivityAtIfLater
  def withLastActivityAt(time: DateTime): KeepToEmail = this.copy(lastActivityAt = time)

  def isActive = state == KeepToEmailStates.ACTIVE
  def isInactive = state == KeepToEmailStates.INACTIVE

  def sanitizeForDelete = this.withState(KeepToEmailStates.INACTIVE)
}

object KeepToEmailStates extends States[KeepToEmail]
