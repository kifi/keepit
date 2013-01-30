package com.keepit.common.mail

import com.keepit.common.logging.Logging
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
import ru.circumflex.orm._
import com.keepit.model.{User, UserCxRepo}

case class ElectronicMailMessageId(val id: String)
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

  def save()(implicit conn: Connection): ElectronicMail = try {
    val entity = ElectronicMailEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  } catch {
    case e => throw new Exception("Error saving email from [%s] %s to %s with html body size %s: %s".format(fromName, from, to, htmlBody.value.size, subject), e)
  }
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
class ElectronicMailRepoImpl @Inject() (val db: DataBaseComponent) extends DbRepo[ElectronicMail] with ElectronicMailRepo with ExternalIdColumnDbFunction[ElectronicMail] {
  import FortyTwoTypeMappers._
  import org.scalaquery.ql._
  import org.scalaquery.ql.ColumnOps._
  import org.scalaquery.ql.basic.BasicProfile
  import org.scalaquery.ql.extended.ExtendedTable
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[ElectronicMail](db, "electronic_mail") with ExternalIdColumn[ElectronicMail] {
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
    def category = column[ElectronicMailCategory]("category", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ senderUserId.? ~ from ~ fromName.? ~ to ~ subject ~ state ~ htmlBody ~ textBody.? ~ responseMessage.? ~ timeSubmitted.? ~ messageId.? ~ category <> (ElectronicMail, ElectronicMail.unapply _)
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
    (for (t <- table if t.to =!= filterRecipeintNot) yield t.id.countDistinct).first

  def page(page: Int, size: Int, filterRecipeintNot: EmailAddressHolder)(implicit session: RSession): Seq[ElectronicMail] =
    (for {
      t <- table if t.to =!= filterRecipeintNot
      _ <- Query.orderBy(t.id desc)
    } yield t).drop(page * size).take(size).list
}

object ElectronicMailStates {
  val PREPARING = State[ElectronicMail]("preparing")
  val READY_TO_SEND = State[ElectronicMail]("ready_to_send")
  val SENT = State[ElectronicMail]("sent")
  val ERROR_SENDING = State[ElectronicMail]("error_sending")
}

object ElectronicMailCx {

  //slicked
  def all(implicit conn: Connection): Seq[ElectronicMail] =
    ElectronicMailEntity.all.map(_.view)

  //slicked
  def get(id: Id[ElectronicMail])(implicit conn: Connection): ElectronicMail =
    ElectronicMailEntity.get(id).map(_.view).getOrElse(throw NotFoundException(id))

  def outbox()(implicit conn: Connection): Seq[ElectronicMail] =
    ((ElectronicMailEntity AS "p") map { p => SELECT (p.*) FROM p WHERE (p.state EQ ElectronicMailStates.READY_TO_SEND ) }).list() map (_.view)

  def get(id: ExternalId[ElectronicMail])(implicit conn: Connection): ElectronicMail =
    ((ElectronicMailEntity AS "p") map { p => SELECT (p.*) FROM p WHERE (p.externalId EQ id ) unique }).getOrElse(throw NotFoundException(id)).view

  def forSender(senderId: Id[User])(implicit conn: Connection): Seq[ElectronicMail] =
    (ElectronicMailEntity AS "p").map { p => SELECT (p.*) FROM p WHERE (p.senderUserId EQ senderId) list
    }.map(_.view)

  def forRecipient(mailAddresses: Seq[String])(implicit conn: Connection): Seq[ElectronicMail] =
    mailAddresses match {
      case Nil => Nil
      case addrs =>
        (ElectronicMailEntity AS "p").map { p => SELECT (p.*) FROM p WHERE (p.to IN (addrs)) list }.map(_.view)
    }

  def page(page: Int, size: Int, filterRecipeintNot: EmailAddressHolder)(implicit conn: Connection): Seq[ElectronicMail] =
    (ElectronicMailEntity AS "p").map { p => SELECT (p.*) FROM p WHERE (p.to NE filterRecipeintNot.address) LIMIT size OFFSET (page * size) ORDER_BY (p.id DESC) list }.map(_.view)

  def count(filterRecipeintNot: EmailAddressHolder)(implicit conn: Connection): Long =
    (ElectronicMailEntity AS "p").map(p => SELECT(COUNT(p.id)).FROM(p).WHERE (p.to NE filterRecipeintNot.address).unique).get

}

private[mail] class ElectronicMailEntity extends Entity[ElectronicMail, ElectronicMailEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val externalId = "external_id".EXTERNAL_ID[ElectronicMail].NOT_NULL(ExternalId())
  val senderUserId = "user_id".ID[User]
  val from = "from_addr".VARCHAR(256).NOT_NULL
  val fromName = "from_name".VARCHAR(256).NOT_NULL
  val to = "to_addr".VARCHAR(256).NOT_NULL
  val subject = "subject".VARCHAR(1024).NOT_NULL
  val state = "state".STATE[ElectronicMail].NOT_NULL
  val htmlBody = "html_body".CLOB.NOT_NULL
  val textBody = "text_body".CLOB
  val responseMessage = "response_message".VARCHAR(1024)
  val timeSubmitted = "time_submitted".JODA_TIMESTAMP
  val messageId = "message_id".VARCHAR(64)
  val category = "category".VARCHAR(64)

  def relation = ElectronicMailEntity

  def view = ElectronicMail(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    externalId = externalId(),
    senderUserId = senderUserId.value,
    from = EmailAddresses(from()),
    fromName = fromName.value,
    to = new EmailAddressHolder(){val address = to()},
    subject = subject(),
    state = state(),
    htmlBody = LargeString(htmlBody()),
    textBody = textBody.value.map(LargeString(_)),
    responseMessage = responseMessage.value,
    timeSubmitted = timeSubmitted.value,
    messageId = messageId.value.map(ElectronicMailMessageId(_)),
    category = ElectronicMailCategory(category())
  )
}

private object ElectronicMailEntity extends ElectronicMailEntity with EntityTable[ElectronicMail, ElectronicMailEntity] {
  override def relationName = "electronic_mail"

  def apply(view: ElectronicMail): ElectronicMailEntity = {
    val entity = new ElectronicMailEntity
    entity.id.set(view.id)
    entity.createdAt := view.createdAt
    entity.updatedAt := view.updatedAt
    entity.externalId := view.externalId
    entity.senderUserId.set(view.senderUserId)
    entity.state := view.state
    entity.from := view.from.address
    entity.fromName.set(view.fromName)
    entity.to := view.to.address
    entity.subject := view.subject
    entity.htmlBody := view.htmlBody.value
    entity.textBody.set(view.textBody.map(_.value))
    entity.responseMessage.set(view.responseMessage)
    entity.timeSubmitted.set(view.timeSubmitted)
    entity.messageId.set(view.messageId.map(_.id))
    entity.category := view.category.category
    entity
  }
}
