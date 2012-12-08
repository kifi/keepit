package com.keepit.common.mail

import com.keepit.common.logging.Logging
import com.keepit.common.db.{Id, Entity, EntityTable, ExternalId, State}
import com.keepit.common.db.NotFoundException
import com.keepit.common.time._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api.Play.current
import ru.circumflex.orm._
import com.keepit.model.User

case class ElectronicMailMessageId(val id: String)
case class ElectronicMailCategory(val category: String)

case class ElectronicMail (
  id: Option[Id[ElectronicMail]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[ElectronicMail] = ExternalId(),
  userId: Option[Id[User]] = None,
  from: SystemEmailAddress,
  fromName: Option[String] = None,
  to: EmailAddressHolder,
  subject: String,
  state: State[ElectronicMail] = ElectronicMail.States.PREPARING,
  htmlBody: String,
  textBody: Option[String] = None,
  responseMessage: Option[String] = None,
  timeSubmitted: Option[DateTime] = None,
  messageId: Option[ElectronicMailMessageId] = None, //of the format 475082848.3.1353745094337.JavaMail.eishay@eishay-mbp.local
  category: ElectronicMailCategory //type of mail in free form, will be use for tracking
) extends Logging {

  def prepareToSend(): ElectronicMail = state match {
    case ElectronicMail.States.PREPARING => copy(state = ElectronicMail.States.READY_TO_SEND)
    case _ => throw new Exception("mail %s in bad state, can't prepare to send".format(this))
  }

  def sent(message: String, messageId: ElectronicMailMessageId): ElectronicMail = state match {
    case ElectronicMail.States.READY_TO_SEND =>
      copy(state = ElectronicMail.States.SENT, responseMessage = Some(message), timeSubmitted = Some(currentDateTime), messageId = Some(messageId))
    case ElectronicMail.States.SENT =>
      log.info("mail already sent. new message is: %s".format(message))
      this
    case _ => throw new Exception("mail %s in bad state, can't prepare to send".format(this))
  }

  def errorSending(message: String): ElectronicMail = copy(state = ElectronicMail.States.ERROR_SENDING, responseMessage = Some(message), timeSubmitted = Some(currentDateTime))

  def save()(implicit conn: Connection): ElectronicMail = try {
    val entity = ElectronicMailEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  } catch {
    case e: Throwable => throw new Exception("Error saving email from [%s] %s to %s with html body size %s: %s".format(fromName, from, to, htmlBody.size, subject), e)
  }
}

object ElectronicMail {

  object States {
    val PREPARING = State[ElectronicMail]("preparing")
    val READY_TO_SEND = State[ElectronicMail]("ready_to_send")
    val SENT = State[ElectronicMail]("sent")
    val ERROR_SENDING = State[ElectronicMail]("error_sending")
  }

  def all(implicit conn: Connection): Seq[ElectronicMail] =
    ElectronicMailEntity.all.map(_.view)

  def get(id: Id[ElectronicMail])(implicit conn: Connection): ElectronicMail =
    ElectronicMailEntity.get(id).map(_.view).getOrElse(throw NotFoundException(id))


  def outbox()(implicit conn: Connection): Seq[ElectronicMail] =
    ((ElectronicMailEntity AS "p") map { p => SELECT (p.*) FROM p WHERE (p.state EQ ElectronicMail.States.READY_TO_SEND ) }).list() map (_.view)

  def get(id: ExternalId[ElectronicMail])(implicit conn: Connection): ElectronicMail =
    ((ElectronicMailEntity AS "p") map { p => SELECT (p.*) FROM p WHERE (p.externalId EQ id ) unique }).getOrElse(throw NotFoundException(id)).view

  def forUser(user: User)(implicit conn: Connection): Seq[ElectronicMail] =
    (ElectronicMailEntity AS "p").map { p =>
      SELECT (p.*) FROM p WHERE (p.userId EQ user.id.get) list
    }.map(_.view)
}

private[mail] class ElectronicMailEntity extends Entity[ElectronicMail, ElectronicMailEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val externalId = "external_id".EXTERNAL_ID[ElectronicMail].NOT_NULL(ExternalId())
  val userId = "user_id".ID[User]
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
    userId = userId.value,
    from = EmailAddresses(from()),
    fromName = fromName.value,
    to = new EmailAddressHolder(){val address = to()},
    subject = subject(),
    state = state(),
    htmlBody = htmlBody(),
    textBody = textBody.value,
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
    entity.userId.set(view.userId)
    entity.state := view.state
    entity.from := view.from.address
    entity.fromName.set(view.fromName)
    entity.to := view.to.address
    entity.subject := view.subject
    entity.htmlBody := view.htmlBody
    entity.textBody.set(view.textBody)
    entity.responseMessage.set(view.responseMessage)
    entity.timeSubmitted.set(view.timeSubmitted)
    entity.messageId.set(view.messageId.map(_.id))
    entity.category := view.category.category
    entity
  }
}
