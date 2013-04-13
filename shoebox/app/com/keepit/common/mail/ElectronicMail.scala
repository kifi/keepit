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
  to: EmailAddressHolder,
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

  def prepareToSend(): ElectronicMail = state match {
    case ElectronicMailStates.PREPARING => copy(state = ElectronicMailStates.READY_TO_SEND)
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

@ImplementedBy(classOf[ElectronicMailRepoImpl])
trait ElectronicMailRepo extends Repo[ElectronicMail] with ExternalIdColumnFunction[ElectronicMail] {
  def outbox()(implicit session: RSession): Seq[ElectronicMail]
  def forSender(senderId: Id[User])(implicit session: RSession): Seq[ElectronicMail]
  def forRecipient(mailAddresses: Seq[String])(implicit session: RSession): Seq[ElectronicMail]
  def count(filterRecipeintNot: EmailAddressHolder)(implicit session: RSession): Int
  def page(page: Int, size: Int, filterRecipeintNot: EmailAddressHolder)(implicit session: RSession): Seq[ElectronicMail]
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
    def to = column[EmailAddressHolder]("to_addr", O.Nullable)
    def subject = column[String]("subject", O.Nullable)
    def htmlBody = column[LargeString]("html_body", O.NotNull)
    def textBody = column[LargeString]("text_body", O.Nullable)
    def responseMessage = column[String]("response_message", O.Nullable)
    def timeSubmitted = column[DateTime]("time_submitted", O.Nullable)
    def messageId = column[ElectronicMailMessageId]("message_id", O.Nullable)
    def inReplyTo = column[ElectronicMailMessageId]("in_reply_to", O.Nullable)
    def category = column[ElectronicMailCategory]("category", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ senderUserId.? ~ from ~ fromName.? ~ to ~ subject ~ state ~
        htmlBody ~ textBody.? ~ responseMessage.? ~ timeSubmitted.? ~ messageId.? ~ inReplyTo.? ~ category <>
        (ElectronicMail, ElectronicMail.unapply _)
  }

  def outbox()(implicit session: RSession): Seq[ElectronicMail] =
    (for (t <- table if t.state === ElectronicMailStates.READY_TO_SEND ) yield t).list()

  def forSender(senderId: Id[User])(implicit session: RSession): Seq[ElectronicMail] =
    (for (t <- table if t.senderUserId === senderId ) yield t).list()

  def forRecipient(mailAddresses: Seq[String])(implicit session: RSession): Seq[ElectronicMail] =
    mailAddresses.map {str => new EmailAddressHolder(){val address = str}} match {
      case Nil => Nil
      case addrs => (for (t <- table if t.to inSet addrs ) yield t).list()
    }

  def count(filterRecipeintNot: EmailAddressHolder)(implicit session: RSession): Int =
    Query((for (t <- table if t.to =!= filterRecipeintNot) yield t.id).countDistinct).first

  def page(page: Int, size: Int, filterRecipeintNot: EmailAddressHolder)(implicit session: RSession): Seq[ElectronicMail] =
    (for ( t <- table if t.to =!= filterRecipeintNot ) yield t).sortBy(_.id desc).drop(page * size).take(size).list
}

object ElectronicMailStates {
  val PREPARING = State[ElectronicMail]("preparing")
  val READY_TO_SEND = State[ElectronicMail]("ready_to_send")
  val SENT = State[ElectronicMail]("sent")
  val ERROR_SENDING = State[ElectronicMail]("error_sending")
}
