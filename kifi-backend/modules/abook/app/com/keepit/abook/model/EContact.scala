package com.keepit.abook.model

import com.keepit.abook.RichContact
import com.keepit.common.cache.{Key, JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics}
import com.keepit.common.db.{ModelWithState, Id, State, States}
import com.keepit.common.logging.AccessLog
import com.keepit.common.mail.{BasicContact, EmailAddress}
import com.keepit.model.{ABookInfo, User}
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import scala.concurrent.duration.Duration
import com.keepit.common.time._

case class EContact(
  id: Option[Id[EContact]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId:    Id[User],
  abookId:   Option[Id[ABookInfo]],
  emailAccountId: Option[Id[EmailAccount]],
  email:     EmailAddress,
  contactUserId: Option[Id[User]] = None,
  name:      Option[String] = None,
  firstName: Option[String] = None,
  lastName:  Option[String] = None,
  state:     State[EContact] = EContactStates.ACTIVE
) extends ModelWithState[EContact] {
  def withId(id: Id[EContact]) = this.copy(id = Some(id))
  def withName(name: Option[String]) = this.copy(name = name)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def updateWith(contactInfo: BasicContact): EContact = {
    require(email.equalsIgnoreCase(contactInfo.email), s"Supplied info $contactInfo does not represent the same email address as $this.")

    val updatedName = contactInfo.name orElse {
      (contactInfo.firstName, contactInfo.lastName) match {
        case (Some(f), Some(l)) => Some(s"$f $l")
        case (Some(f), None) => Some(f)
        case (None, Some(l)) => Some(l)
        case (None, None) => None
      }
    } orElse name

    this.copy(
      email = contactInfo.email,
      name = updatedName,
      firstName = contactInfo.firstName orElse firstName,
      lastName = contactInfo.lastName orElse lastName
    )
  }
}

object EContactStates extends States[EContact] {
  val HIDDEN = State[EContact]("hidden")
}

object EContact {
  implicit val format = (
      (__ \ 'id).formatNullable(Id.format[EContact]) and
      (__ \ 'createdAt).format[DateTime] and
      (__ \ 'updatedAt).format[DateTime] and
      (__ \ 'userId).format(Id.format[User]) and
      (__ \ 'abookId).formatNullable(Id.format[ABookInfo]) and
      (__ \ 'emailAccountId).formatNullable(Id.format[EmailAccount]) and
      (__ \ 'email).format[EmailAddress] and
      (__ \ 'contactUserId).formatNullable(Id.format[User]) and
      (__ \ 'name).formatNullable[String] and
      (__ \ 'firstName).formatNullable[String] and
      (__ \ 'lastName).formatNullable[String] and
      (__ \ 'state).format(State.format[EContact])
    )(EContact.apply, unlift(EContact.unapply))

  def toRichContact(econtact: EContact): RichContact = RichContact(econtact.email, econtact.name, econtact.firstName, econtact.lastName, econtact.contactUserId)

  def make(userId: Id[User], abookId: Id[ABookInfo], emailAccount: EmailAccount, contact: BasicContact): EContact = {
    EContact(
      userId = userId,
      abookId = Some(abookId),
      emailAccountId = Some(emailAccount.id.get),
      email = emailAccount.address,
      contactUserId = emailAccount.userId
    ).updateWith(contact)
  }
}

class EContactCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[EContactKey, EContact](stats, accessLog, inner, outer: _*)

case class EContactKey(id: Id[EContact]) extends Key[EContact] {
  val namespace = "econtact"
  override val version = 1
  def toKey(): String = id.id.toString
}
