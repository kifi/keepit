package com.keepit.eliza.model

import com.keepit.common.mail.EmailAddress
import com.keepit.social.{ BasicNonUser, NonUserKinds, NonUserKind }
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db._
import com.keepit.model.{ Keep, User, NormalizedURI }
import play.api.libs.json.{ JsString, JsObject, JsError, JsValue, Format }

case class NonUserThread(
    id: Option[Id[NonUserThread]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    createdBy: Id[User],
    participant: NonUserParticipant,
    keepId: Id[Keep],
    uriId: Option[Id[NormalizedURI]],
    notifiedCount: Int,
    lastNotifiedAt: Option[DateTime],
    threadUpdatedByOtherAt: Option[DateTime],
    muted: Boolean = false,
    state: State[NonUserThread] = NonUserThreadStates.ACTIVE,
    accessToken: ThreadAccessToken = ThreadAccessToken()) extends ModelWithState[NonUserThread] with ParticipantThread {
  def isActive = state == UserThreadStates.ACTIVE

  def withId(id: Id[NonUserThread]): NonUserThread = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt = updateTime)
  def withState(state: State[NonUserThread]) = copy(state = state)

  def withUriId(uriId: Option[Id[NormalizedURI]]) = this.copy(uriId = uriId)

  def sanitizeForDelete = this.copy(state = NonUserThreadStates.INACTIVE)
}

object NonUserThreadStates extends States[NonUserThread]

object NonUserThread {
  def forMessageThread(mt: MessageThread)(nu: NonUserParticipant) = NonUserThread(
    createdBy = mt.startedBy,
    participant = nu,
    keepId = mt.keepId,
    uriId = Some(mt.uriId),
    notifiedCount = 0,
    lastNotifiedAt = None,
    threadUpdatedByOtherAt = None
  )
}

sealed trait NonUserParticipant {
  val identifier: String
  val referenceId: Option[String]
  val kind: NonUserKind

  override def toString() = identifier.toString

  def shortName: String
  def fullName: String
}
object NonUserParticipant {
  implicit val format = new Format[NonUserParticipant] {
    // fields are shortened for overhead reasons
    def reads(json: JsValue) = {
      // k == "kind"
      // i == "identifier"
      // r == "referenceId"
      (json \ "k").validate[String].flatMap {
        case NonUserKinds.email.name => for {
          email <- (json \ "i").validate[EmailAddress]
        } yield NonUserEmailParticipant(email)
        case unsupportedKind => JsError(s"Unsupported NonUserKind: $unsupportedKind")
      }
    }
    def writes(p: NonUserParticipant): JsValue = {
      JsObject(Seq(Some("k" -> JsString(p.kind.name)), Some("i" -> JsString(p.identifier)), p.referenceId.map(r => "r" -> JsString(r))).flatten)
    }
  }

  def toBasicNonUser(nonUser: NonUserParticipant) = {
    // todo: Add other data, like econtact data
    BasicNonUser(kind = nonUser.kind, id = nonUser.identifier, firstName = Some(nonUser.identifier), lastName = None, BasicNonUser.defaultEmailPictureName)
  }

  def fromBasicNonUser(nonUser: BasicNonUser) = nonUser.kind match {
    case NonUserKinds.email => NonUserEmailParticipant(EmailAddress(nonUser.id))
  }

  def toEmailAddress(nonUser: NonUserParticipant): Option[EmailAddress] = nonUser match {
    case NonUserEmailParticipant(email) => Some(email)
    case _ => None
  }
}

case class NonUserEmailParticipant(address: EmailAddress) extends NonUserParticipant {
  val identifier = address.address
  val referenceId = Some(address.address)
  val kind = NonUserKinds.email

  def shortName = identifier
  def fullName = identifier
}
