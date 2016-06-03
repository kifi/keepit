package com.keepit.eliza.model

import com.keepit.common.mail.EmailAddress
import com.keepit.social.{ BasicNonUser, NonUserKinds, NonUserKind }
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db._
import com.keepit.model.{ Keep, User, NormalizedURI }
import play.api.libs.json._

case class NonUserThread(
    id: Option[Id[NonUserThread]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    createdBy: Id[User],
    participant: EmailParticipant,
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
  def forMessageThread(mt: MessageThread)(nu: EmailParticipant) = NonUserThread(
    createdBy = mt.startedBy,
    participant = nu,
    keepId = mt.keepId,
    uriId = Some(mt.uriId),
    notifiedCount = 0,
    lastNotifiedAt = None,
    threadUpdatedByOtherAt = None
  )
}

case class EmailParticipant(address: EmailAddress) {
  val identifier = address.address
  val referenceId = Some(address.address)
  val kind = NonUserKinds.email

  def shortName = identifier
  def fullName = identifier

  override def toString = identifier.toString
}
object EmailParticipant {
  implicit val format: Format[EmailParticipant] = Format(
    Reads { json => (json \ "i").validate[EmailAddress].map(email => EmailParticipant(email)) },
    Writes { p => Json.obj("i" -> JsString(p.identifier)) }
  )

  def toBasicNonUser(nonUser: EmailParticipant) = {
    BasicNonUser(kind = nonUser.kind, id = nonUser.identifier, firstName = Some(nonUser.identifier), lastName = None, BasicNonUser.DefaultPictureName)
  }

  def toEmailAddress(nonUser: EmailParticipant): Option[EmailAddress] = Some(nonUser.address)
}
