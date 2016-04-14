package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ Id, State }
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ EmailAddressHash, EmailAddress }
import com.keepit.common.time.Clock
import org.joda.time.DateTime

@ImplementedBy(classOf[KeepToEmailRepoImpl])
trait KeepToEmailRepo extends Repo[KeepToEmail] {
  def getByKeepIdAndEmailAddress(keepId: Id[Keep], emailAddress: EmailAddress, excludeStateOpt: Option[State[KeepToEmail]] = Some(KeepToEmailStates.INACTIVE))(implicit session: RSession): Option[KeepToEmail]
  def getAllByKeepIds(keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Seq[KeepToEmail]]
  def getAllByKeepId(keepId: Id[Keep], excludeStateOpt: Option[State[KeepToEmail]] = Some(KeepToEmailStates.INACTIVE))(implicit session: RSession): Seq[KeepToEmail]
  def deactivate(model: KeepToEmail)(implicit session: RWSession): Unit
}

@Singleton
class KeepToEmailRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends KeepToEmailRepo with DbRepo[KeepToEmail] with Logging {

  override def deleteCache(ktu: KeepToEmail)(implicit session: RSession) {}
  override def invalidateCache(ktu: KeepToEmail)(implicit session: RSession) {}

  import db.Driver.simple._

  type RepoImpl = KeepToEmailTable
  class KeepToEmailTable(tag: Tag) extends RepoTable[KeepToEmail](db, tag, "keep_to_email") {
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def emailAddress = column[EmailAddress]("email_address", O.NotNull)
    def emailAddressHash = column[EmailAddressHash]("email_address_hash", O.NotNull)
    def addedAt = column[DateTime]("added_at", O.NotNull)
    def addedBy = column[Option[Id[User]]]("added_by", O.Nullable)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def lastActivityAt = column[DateTime]("last_activity_at", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, keepId, emailAddress, emailAddressHash, addedAt, addedBy, uriId, lastActivityAt) <> ((fromDbRow _).tupled, toDbRow)
  }

  private def fromDbRow(id: Option[Id[KeepToEmail]], createdAt: DateTime, updatedAt: DateTime, state: State[KeepToEmail],
    keepId: Id[Keep], emailAddress: EmailAddress, emailAddressHash: EmailAddressHash, addedAt: DateTime, addedBy: Option[Id[User]],
    uriId: Id[NormalizedURI], lastActivityAt: DateTime): KeepToEmail = {
    KeepToEmail(
      id, createdAt, updatedAt, state,
      keepId, emailAddress, addedAt, addedBy,
      uriId, lastActivityAt)
  }

  private def toDbRow(kte: KeepToEmail) = {
    Some(
      (kte.id, kte.createdAt, kte.updatedAt, kte.state,
        kte.keepId, kte.emailAddress, kte.emailAddressHash, kte.addedAt, kte.addedBy,
        kte.uriId, kte.lastActivityAt)
    )
  }

  def table(tag: Tag) = new KeepToEmailTable(tag)
  initTable()

  def activeRows = rows.filter(_.state === KeepToEmailStates.ACTIVE)
  def getByKeepIdAndEmailAddress(keepId: Id[Keep], emailAddress: EmailAddress, excludeStateOpt: Option[State[KeepToEmail]] = Some(KeepToEmailStates.INACTIVE))(implicit session: RSession): Option[KeepToEmail] = {
    val hash = EmailAddressHash.hashEmailAddress(emailAddress)
    rows.filter(r => r.keepId === keepId && r.emailAddressHash === hash && r.emailAddress === emailAddress && r.state =!= excludeStateOpt.orNull).firstOption
  }
  def getAllByKeepIds(keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], Seq[KeepToEmail]] = {
    activeRows.filter(_.keepId.inSet(keepIds)).list.groupBy(_.keepId)
  }
  def getAllByKeepId(keepId: Id[Keep], excludeStateOpt: Option[State[KeepToEmail]] = Option(KeepToEmailStates.INACTIVE))(implicit session: RSession): Seq[KeepToEmail] = {
    rows.filter(r => r.keepId === keepId && r.state =!= excludeStateOpt.orNull).list
  }

  def deactivate(model: KeepToEmail)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }
}
