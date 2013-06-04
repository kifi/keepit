package com.keepit.common.mail

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.LargeString._
import com.keepit.common.time._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api.Play.current
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
      (__ \ 'createdAt).format[DateTime] and
      (__ \ 'updatedAt).format[DateTime] and
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

@ImplementedBy(classOf[ElectronicMailRepoImpl])
trait ElectronicMailRepo extends Repo[ElectronicMail] with ExternalIdColumnFunction[ElectronicMail] {
  def getOpt(id: Id[ElectronicMail])(implicit session: RSession): Option[ElectronicMail]
  def outbox()(implicit session: RSession): Seq[Id[ElectronicMail]]
  def forSender(senderId: Id[User])(implicit session: RSession): Seq[ElectronicMail]
}

@Singleton
class ElectronicMailRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[ElectronicMail] with ElectronicMailRepo with ExternalIdColumnDbFunction[ElectronicMail] {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._
  import scala.slick.lifted.Query
  import DBSession._

  override val table = new RepoTable[ElectronicMail](db, "electronic_mail") with ExternalIdColumn[ElectronicMail] {
    def senderUserId = column[Id[User]]("user_id", O.Nullable)
    def from = column[SystemEmailAddress]("from_addr", O.NotNull)
    def fromName = column[String]("from_name", O.Nullable)
    def to = column[Seq[EmailAddressHolder]]("to_addr", O.Nullable)
    def cc = column[Seq[EmailAddressHolder]]("cc_addr", O.Nullable)
    def subject = column[String]("subject", O.Nullable)
    def htmlBody = column[LargeString]("html_body", O.NotNull)
    def textBody = column[LargeString]("text_body", O.Nullable)
    def responseMessage = column[String]("response_message", O.Nullable)
    def timeSubmitted = column[DateTime]("time_submitted", O.Nullable)
    def messageId = column[ElectronicMailMessageId]("message_id", O.Nullable)
    def inReplyTo = column[ElectronicMailMessageId]("in_reply_to", O.Nullable)
    def category = column[ElectronicMailCategory]("category", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ senderUserId.? ~ from ~ fromName.? ~ to ~ cc ~ subject ~ state ~
        htmlBody ~ textBody.? ~ responseMessage.? ~ timeSubmitted.? ~ messageId.? ~ inReplyTo.? ~ category <>
        (ElectronicMail.apply _, ElectronicMail.unapply _)
  }

  def getOpt(id: Id[ElectronicMail])(implicit session: RSession): Option[ElectronicMail] = (for(f <- table if f.id is id) yield f).firstOption

  def outbox()(implicit session: RSession): Seq[Id[ElectronicMail]] =
    (for (t <- table if t.state === ElectronicMailStates.READY_TO_SEND ) yield t.id).list()

  def forSender(senderId: Id[User])(implicit session: RSession): Seq[ElectronicMail] =
    (for (t <- table if t.senderUserId === senderId ) yield t).list()
}

object ElectronicMailStates {
  val PREPARING = State[ElectronicMail]("preparing")
  val READY_TO_SEND = State[ElectronicMail]("ready_to_send")
  val SENT = State[ElectronicMail]("sent")
  val ERROR_SENDING = State[ElectronicMail]("error_sending")
}
