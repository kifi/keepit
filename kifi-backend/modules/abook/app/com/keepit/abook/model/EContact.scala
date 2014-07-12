package com.keepit.abook.model

import com.keepit.abook.RichContact
import com.keepit.common.cache.{ Key, JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics }
import com.keepit.common.db.{ ModelWithState, Id, State, States }
import com.keepit.common.logging.AccessLog
import com.keepit.common.mail.{ BasicContact, EmailAddress }
import com.keepit.model.{ ABookInfo, User }
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import scala.concurrent.duration.Duration
import com.keepit.common.time._

case class EContact(
    id: Option[Id[EContact]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    abookId: Id[ABookInfo],
    emailAccountId: Id[EmailAccount],
    email: EmailAddress,
    contactUserId: Option[Id[User]] = None,
    name: Option[String] = None,
    firstName: Option[String] = None,
    lastName: Option[String] = None,
    state: State[EContact] = EContactStates.ACTIVE) extends ModelWithState[EContact] {
  def withId(id: Id[EContact]) = this.copy(id = Some(id))
  def withName(name: Option[String]) = this.copy(name = name)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def updateWith(contacts: BasicContact*): EContact = EContact.update(this, contacts)
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
    (__ \ 'abookId).format(Id.format[ABookInfo]) and
    (__ \ 'emailAccountId).format(Id.format[EmailAccount]) and
    (__ \ 'email).format[EmailAddress] and
    (__ \ 'contactUserId).formatNullable(Id.format[User]) and
    (__ \ 'name).formatNullable[String] and
    (__ \ 'firstName).formatNullable[String] and
    (__ \ 'lastName).formatNullable[String] and
    (__ \ 'state).format(State.format[EContact])
  )(EContact.apply, unlift(EContact.unapply))

  def toRichContact(econtact: EContact): RichContact = RichContact(econtact.email, econtact.name, econtact.firstName, econtact.lastName, econtact.contactUserId)

  def make(userId: Id[User], abookId: Id[ABookInfo], emailAccount: EmailAccount, contacts: BasicContact*): EContact = {
    val eContact = EContact(
      userId = userId,
      abookId = abookId,
      emailAccountId = emailAccount.id.get,
      email = emailAccount.address,
      contactUserId = emailAccount.userId
    )
    update(eContact, contacts)
  }

  def update(eContact: EContact, contacts: Seq[BasicContact]): EContact = contacts.foldLeft(eContact) {
    case (currentEContact, moreInfo) =>
      require(moreInfo.email.equalsIgnoreCase(currentEContact.email), s"EmailAddress from $moreInfo does not match $currentEContact.")
      val updatedName = moreInfo.name orElse {
        (moreInfo.firstName, moreInfo.lastName) match {
          case (Some(f), Some(l)) => Some(s"$f $l")
          case (Some(f), None) => Some(f)
          case (None, Some(l)) => Some(l)
          case (None, None) => None
        }
      } orElse currentEContact.name

      currentEContact.copy(
        email = moreInfo.email,
        name = updatedName,
        firstName = moreInfo.firstName orElse currentEContact.firstName,
        lastName = moreInfo.lastName orElse currentEContact.lastName
      )
  }
}

class EContactCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[EContactKey, EContact](stats, accessLog, inner, outer: _*)

case class EContactKey(id: Id[EContact]) extends Key[EContact] {
  val namespace = "econtact"
  override val version = 1
  def toKey(): String = id.id.toString
}
