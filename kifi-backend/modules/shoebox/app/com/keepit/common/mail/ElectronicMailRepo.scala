package com.keepit.common.mail

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock
import com.keepit.common.db.{ LargeString, Id }
import com.keepit.model.User
import org.joda.time.DateTime
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }

@ImplementedBy(classOf[ElectronicMailRepoImpl])
trait ElectronicMailRepo extends Repo[ElectronicMail] with ExternalIdColumnFunction[ElectronicMail] {
  def getOpt(id: Id[ElectronicMail])(implicit session: RSession): Option[ElectronicMail]
  def outbox()(implicit session: RSession): Seq[Id[ElectronicMail]]
  def forSender(senderId: Id[User])(implicit session: RSession): Seq[ElectronicMail]
}

@Singleton
class ElectronicMailRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[ElectronicMail] with ElectronicMailRepo with ExternalIdColumnDbFunction[ElectronicMail] {

  import db.Driver.simple._

  type RepoImpl = ElectronicMailTable
  class ElectronicMailTable(tag: Tag) extends RepoTable[ElectronicMail](db, tag, "electronic_mail") with ExternalIdColumn[ElectronicMail] {
    def senderUserId = column[Id[User]]("user_id", O.Nullable)
    def from = column[EmailAddress]("from_addr", O.NotNull)
    def fromName = column[String]("from_name", O.Nullable)
    def to = column[Seq[EmailAddress]]("to_addr", O.Nullable)
    def cc = column[Seq[EmailAddress]]("cc_addr", O.Nullable)
    def subject = column[String]("subject", O.Nullable)
    def htmlBody = column[LargeString]("html_body", O.NotNull)
    def textBody = column[LargeString]("text_body", O.Nullable)
    def responseMessage = column[String]("response_message", O.Nullable)
    def timeSubmitted = column[DateTime]("time_submitted", O.Nullable)
    def messageId = column[ElectronicMailMessageId]("message_id", O.Nullable)
    def inReplyTo = column[ElectronicMailMessageId]("in_reply_to", O.Nullable)
    def category = column[ElectronicMailCategory]("category", O.NotNull)
    def extraHeaders = column[Map[String, String]]("extra_headers", O.Nullable)
    def * = (id.?, createdAt, updatedAt, externalId, senderUserId.?, from, fromName.?, to, cc, subject, state,
      htmlBody, textBody.?, responseMessage.?, timeSubmitted.?, messageId.?, inReplyTo.?, category, extraHeaders.?) <>
      ((ElectronicMail.apply _).tupled, ElectronicMail.unapply _)
  }

  def table(tag: Tag) = new ElectronicMailTable(tag)
  initTable()

  override def save(mail: ElectronicMail)(implicit session: RWSession) = super.save(mail.clean())

  override def deleteCache(model: ElectronicMail)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: ElectronicMail)(implicit session: RSession): Unit = {}

  def getOpt(id: Id[ElectronicMail])(implicit session: RSession): Option[ElectronicMail] = (for (f <- rows if f.id is id) yield f).firstOption

  def outbox()(implicit session: RSession): Seq[Id[ElectronicMail]] =
    (for (t <- rows if t.state === ElectronicMailStates.READY_TO_SEND) yield t.id).list

  def forSender(senderId: Id[User])(implicit session: RSession): Seq[ElectronicMail] =
    (for (t <- rows if t.senderUserId === senderId) yield t).list
}
