package com.keepit.common.mail

import com.keepit.common.db._
import com.keepit.common.db.LargeString._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.model.User

case class ElectronicMailMessageId(id: String) {
  def toEmailHeader = s"<$id>"
}
object ElectronicMailMessageId {
  private val MessageIdWithAngleBrackets = """^<(.*)>$""".r
  def fromEmailHeader(value: String): ElectronicMailMessageId =
    ElectronicMailMessageId(value.trim match {
      case MessageIdWithAngleBrackets(id) => id
      case id => id
    })
}
case class ElectronicMailCategory(val category: String)

case class ElectronicMail (
  id: Option[Id[ElectronicMail]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[ElectronicMail] = ExternalId(),
  senderUserId: Option[Id[User]] = None,
  from: SystemEmailAddress,
  fromName: Option[String] = None,
  to: Seq[EmailAddressHolder] = Seq[EmailAddressHolder](),
  cc: Seq[EmailAddressHolder] = Seq[EmailAddressHolder](),
  subject: String,
  state: State[ElectronicMail] = ElectronicMailStates.PREPARING,
  htmlBody: LargeString,
  textBody: Option[LargeString] = None,
  responseMessage: Option[String] = None,
  timeSubmitted: Option[DateTime] = None,
  messageId: Option[ElectronicMailMessageId] = None, //of the format 475082848.3.1353745094337.JavaMail.eishay@eishay-mbp.local
  inReplyTo: Option[ElectronicMailMessageId] = None,
  category: ElectronicMailCategory //type of mail in free form, will be use for tracking
) extends ModelWithExternalId[ElectronicMail] {
  def withId(id: Id[ElectronicMail]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

  def isReadyToSend: Boolean = state == ElectronicMailStates.READY_TO_SEND

  def prepareToSend(): ElectronicMail = state match {
    case ElectronicMailStates.PREPARING => copy(
      state = ElectronicMailStates.READY_TO_SEND,
      htmlBody = htmlBody.value.take(8*1024*1024),
      textBody = textBody.map(_.value.take(8*1024*1024)))
    case _ => throw new Exception("mail %s in bad state, can't prepare to send".format(this))
  }

  def sent(message: String, messageId: ElectronicMailMessageId): ElectronicMail = state match {
    case ElectronicMailStates.READY_TO_SEND =>
      copy(state = ElectronicMailStates.SENT, responseMessage = Some(message), timeSubmitted = Some(currentDateTime), messageId = Some(messageId))
    case ElectronicMailStates.SENT =>
      this
    case _ => throw new Exception("mail %s in bad state, can't prepare to send".format(this))
  }

  def errorSending(message: String): ElectronicMail = copy(state = ElectronicMailStates.ERROR_SENDING, responseMessage = Some(message), timeSubmitted = Some(currentDateTime))
}

object ElectronicMail {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  import com.keepit.common.db.Id

  implicit val userExternalIdFormat = ExternalId.format[User]
  implicit val emailExternalIdFormat = ExternalId.format[ElectronicMail]
  implicit val idFormat = Id.format[ElectronicMail]
  implicit val fromFormat: Format[SystemEmailAddress] =
    Format(__.read[String].map(s => EmailAddresses(s)), new Writes[SystemEmailAddress]{ def writes(o: SystemEmailAddress) = JsString(o.address) })
  implicit val emailAddressHolderFormat: Format[EmailAddressHolder] =
    Format(__.read[String].map(s => GenericEmailAddress(s)), new Writes[EmailAddressHolder]{ def writes(o: EmailAddressHolder) = JsString(o.address) })
  implicit val emailMessageIdFormat: Format[ElectronicMailMessageId] =
    Format(__.read[String].map(s => ElectronicMailMessageId(s)), new Writes[ElectronicMailMessageId]{ def writes(o: ElectronicMailMessageId) = JsString(o.id) })
  implicit val emailCategoryFormat: Format[ElectronicMailCategory] =
    Format(__.read[String].map(s => ElectronicMailCategory(s)), new Writes[ElectronicMailCategory]{ def writes(o: ElectronicMailCategory) = JsString(o.category) })

  implicit val emailFormat = (
      (__ \ 'id).formatNullable(Id.format[ElectronicMail]) and
      (__ \ 'createdAt).format(DateTimeJsonFormat) and
      (__ \ 'updatedAt).format(DateTimeJsonFormat) and
      (__ \ 'externalId).format(ExternalId.format[ElectronicMail]) and
      (__ \ 'senderUserId).formatNullable(Id.format[User]) and
      (__ \ 'from).format[SystemEmailAddress] and
      (__ \ 'fromName).formatNullable[String] and
      (__ \ 'to).format[Seq[EmailAddressHolder]] and
      (__ \ 'cc).format[Seq[EmailAddressHolder]] and
      (__ \ 'subject).format[String] and
      (__ \ 'state).format(State.format[ElectronicMail]) and
      (__ \ 'htmlBody).format[LargeString] and
      (__ \ 'textBody).formatNullable[LargeString] and
      (__ \ 'responseMessage).formatNullable[String] and
      (__ \ 'timeSubmitted).formatNullable[DateTime] and
      (__ \ 'messageId).formatNullable[ElectronicMailMessageId] and
      (__ \ 'inReplyTo).formatNullable[ElectronicMailMessageId] and
      (__ \ 'category).format[ElectronicMailCategory]
  )(ElectronicMail.apply, unlift(ElectronicMail.unapply))
}

object ElectronicMailStates {
  val PREPARING = State[ElectronicMail]("preparing")
  val READY_TO_SEND = State[ElectronicMail]("ready_to_send")
  val SENT = State[ElectronicMail]("sent")
  val ERROR_SENDING = State[ElectronicMail]("error_sending")
}
