package com.keepit.model

import com.keepit.common.db.{ ExternalId, Id, Model }
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.kifi.macros.json
import org.joda.time.DateTime

case class DelightedUser(
    id: Option[Id[DelightedUser]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    delightedExtUserId: String, // Assigned by Delighted
    userId: Id[User],
    email: Option[EmailAddress],
    userLastInteracted: Option[DateTime]) extends Model[DelightedUser] {
  def withId(id: Id[DelightedUser]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

// Contains the information used to register a user to Delighted
@json case class DelightedUserRegistrationInfo(
  userId: Id[User],
  externalId: ExternalId[User],
  email: Option[EmailAddress],
  name: String)
