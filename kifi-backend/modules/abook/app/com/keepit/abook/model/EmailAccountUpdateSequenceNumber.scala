package com.keepit.abook.model

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.{ Model, SequenceNumber, Id }
import org.joda.time.DateTime

import com.keepit.common.time._
import com.keepit.model.EmailAccountUpdate

case class EmailAccountUpdateSequenceNumber(
    id: Option[Id[EmailAccountUpdateSequenceNumber]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    seq: SequenceNumber[EmailAccountUpdate] = SequenceNumber.ZERO) extends Model[EmailAccountUpdateSequenceNumber] {
  def withId(id: Id[EmailAccountUpdateSequenceNumber]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withSeq(newSeq: SequenceNumber[EmailAccountUpdate]) = this.copy(seq = newSeq)
}

@ImplementedBy(classOf[EmailAccountUpdateSequenceNumberRepoImpl])
trait EmailAccountUpdateSequenceNumberRepo extends Repo[EmailAccountUpdateSequenceNumber] {
  def get()(implicit session: RSession): SequenceNumber[EmailAccountUpdate]
  def set(seq: SequenceNumber[EmailAccountUpdate])(implicit session: RWSession): Unit
}

@Singleton
class EmailAccountUpdateSequenceNumberRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[EmailAccountUpdateSequenceNumber] with EmailAccountUpdateSequenceNumberRepo {

  import db.Driver.simple._

  type RepoImpl = EmailAccountUpdateSequenceNumberTable
  class EmailAccountUpdateSequenceNumberTable(tag: Tag) extends RepoTable[EmailAccountUpdateSequenceNumber](db, tag, "email_account_update_seq") {
    def seq = column[SequenceNumber[EmailAccountUpdate]]("seq", O.NotNull)
    def * = (id.?, createdAt, updatedAt, seq) <> ((EmailAccountUpdateSequenceNumber.apply _).tupled, EmailAccountUpdateSequenceNumber.unapply _)
  }

  def table(tag: Tag) = new EmailAccountUpdateSequenceNumberTable(tag)
  initTable()

  override def deleteCache(emailAccountSeqNum: EmailAccountUpdateSequenceNumber)(implicit session: RSession): Unit = {}
  override def invalidateCache(emailAccountSeqNum: EmailAccountUpdateSequenceNumber)(implicit session: RSession): Unit = {}

  private def current()(implicit session: RSession): EmailAccountUpdateSequenceNumber = {
    (for (row <- rows) yield row).firstOption getOrElse EmailAccountUpdateSequenceNumber()
  }

  def get()(implicit session: RSession): SequenceNumber[EmailAccountUpdate] = { current().seq }

  def set(seq: SequenceNumber[EmailAccountUpdate])(implicit session: RWSession): Unit = { save(current().withSeq(seq)) }
}
