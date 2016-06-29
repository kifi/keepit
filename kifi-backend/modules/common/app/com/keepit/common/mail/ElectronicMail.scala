package com.keepit.common.mail

import com.keepit.common.db._
import com.keepit.common.db.LargeString._
import com.keepit.common.strings.StringWithNoLineBreaks
import com.keepit.common.time._
import com.keepit.common.strings._
import com.keepit.model.User

import org.joda.time.DateTime

import play.api.mvc.PathBindable
import play.twirl.api.Html

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
object ElectronicMailCategory {
  implicit def pathBinder = new PathBindable[ElectronicMailCategory] {
    override def bind(key: String, value: String): Either[String, ElectronicMailCategory] =
      Right(ElectronicMailCategory(value))

    override def unbind(key: String, value: ElectronicMailCategory): String = value.category
  }
}

case class ElectronicMail(
    id: Option[Id[ElectronicMail]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    externalId: ExternalId[ElectronicMail] = ExternalId(),
    senderUserId: Option[Id[User]] = None,
    from: EmailAddress,
    fromName: Option[String] = None,
    to: Seq[EmailAddress] = Seq[EmailAddress](),
    cc: Seq[EmailAddress] = Seq[EmailAddress](),
    subject: String,
    state: State[ElectronicMail] = ElectronicMailStates.PREPARING,
    htmlBody: LargeString,
    textBody: Option[LargeString] = None,
    responseMessage: Option[String] = None,
    timeSubmitted: Option[DateTime] = None,
    messageId: Option[ElectronicMailMessageId] = None, //of the format 475082848.3.1353745094337.JavaMail.eishay@eishay-mbp.local
    inReplyTo: Option[ElectronicMailMessageId] = None,
    category: ElectronicMailCategory, //type of mail in free form, will be use for tracking
    extraHeaders: Option[Map[String, String]] = None) extends ModelWithExternalId[ElectronicMail] {

  def clean(): ElectronicMail = copy(subject = subject.trimAndRemoveLineBreaks)

  if (subject.length > 1024) {
    throw new IllegalArgumentException(s"email subject length is ${subject.length} (more then 1024 chars): $subject")
  }

  if (!SystemEmailAddress.isValid(from)) {
    throw new IllegalArgumentException(s"$from is not a system email.")
  }

  def withId(id: Id[ElectronicMail]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

  def isReadyToSend: Boolean = state == ElectronicMailStates.READY_TO_SEND

  def prepareToSend(): ElectronicMail = state match {
    case ElectronicMailStates.PREPARING => copy(
      state = ElectronicMailStates.READY_TO_SEND,
      htmlBody = htmlBody.value.take(8 * 1024 * 1024),
      textBody = textBody.map(_.value.take(8 * 1024 * 1024)))
    case _ => throw new Exception("mail %s in bad state, can't prepare to send".format(this))
  }

  def sent(message: String, messageId: ElectronicMailMessageId): ElectronicMail = state match {
    case ElectronicMailStates.READY_TO_SEND =>
      copy(state = ElectronicMailStates.SENT,
        responseMessage = Some(message.abbreviate(1000)), timeSubmitted = Some(currentDateTime), messageId = Some(messageId))
    case ElectronicMailStates.SENT =>
      this
    case _ => throw new Exception("mail %s in bad state, can't prepare to send".format(this))
  }

  def error(message: String): ElectronicMail =
    copy(state = ElectronicMailStates.ERROR_CREATING,
      responseMessage = Some(message.abbreviate(1000)))

  def errorSending(message: String): ElectronicMail =
    copy(state = ElectronicMailStates.ERROR_SENDING,
      responseMessage = Some(message.abbreviate(1000)), timeSubmitted = Some(currentDateTime))
}

object ElectronicMail {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  import com.keepit.common.db.Id

  implicit val userExternalIdFormat = ExternalId.format[User]
  implicit val emailExternalIdFormat = ExternalId.format[ElectronicMail]
  implicit val idFormat = Id.format[ElectronicMail]
  implicit val emailMessageIdFormat: Format[ElectronicMailMessageId] =
    Format(__.read[String].map(s => ElectronicMailMessageId(s)), new Writes[ElectronicMailMessageId] { def writes(o: ElectronicMailMessageId) = JsString(o.id) })
  implicit val emailCategoryFormat: Format[ElectronicMailCategory] =
    Format(__.read[String].map(s => ElectronicMailCategory(s)), new Writes[ElectronicMailCategory] { def writes(o: ElectronicMailCategory) = JsString(o.category) })

  implicit val emailFormat = (
    (__ \ 'id).formatNullable(Id.format[ElectronicMail]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'externalId).format(ExternalId.format[ElectronicMail]) and
    (__ \ 'senderUserId).formatNullable(Id.format[User]) and
    (__ \ 'from).format[EmailAddress] and
    (__ \ 'fromName).formatNullable[String] and
    (__ \ 'to).format[Seq[EmailAddress]] and
    (__ \ 'cc).format[Seq[EmailAddress]] and
    (__ \ 'subject).format[String] and
    (__ \ 'state).format(State.format[ElectronicMail]) and
    (__ \ 'htmlBody).format[LargeString] and
    (__ \ 'textBody).formatNullable[LargeString] and
    (__ \ 'responseMessage).formatNullable[String] and
    (__ \ 'timeSubmitted).formatNullable[DateTime] and
    (__ \ 'messageId).formatNullable[ElectronicMailMessageId] and
    (__ \ 'inReplyTo).formatNullable[ElectronicMailMessageId] and
    (__ \ 'category).format[ElectronicMailCategory] and
    (__ \ 'extraHeaders).formatNullable[Map[String, String]]
  )(ElectronicMail.apply, unlift(ElectronicMail.unapply))
}

object ElectronicMailStates {
  val PREPARING = State[ElectronicMail]("preparing")
  val OPT_OUT = State[ElectronicMail]("opt_out")
  val USER_INACTIVE = State[ElectronicMail]("user_inactive")
  val READY_TO_SEND = State[ElectronicMail]("ready_to_send")
  val SENT = State[ElectronicMail]("sent")
  val ERROR_SENDING = State[ElectronicMail]("error_sending")
  val ERROR_CREATING = State[ElectronicMail]("error_creating")
}
