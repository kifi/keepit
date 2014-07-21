package com.keepit.model

import java.math.BigInteger
import java.security.SecureRandom

import org.joda.time.{ Period, DateTime }

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ State, Id }
import com.keepit.common.time._
import com.keepit.common.strings
import com.keepit.common.mail.{ EmailAddress }
import com.keepit.common.mail.ElectronicMailCategory

@ImplementedBy(classOf[EmailOptOutRepoImpl])
trait EmailOptOutRepo extends Repo[EmailOptOut] {
  def getByEmailAddress(address: EmailAddress, excludeState: Option[State[EmailOptOut]] = Some(EmailOptOutStates.INACTIVE))(implicit session: RSession): Seq[EmailOptOut]
  def hasOptedOut(address: EmailAddress, category: ElectronicMailCategory = NotificationCategory.ALL)(implicit session: RSession): Boolean
  def optOut(address: EmailAddress, category: ElectronicMailCategory)(implicit session: RWSession): Unit
  def optIn(address: EmailAddress, category: ElectronicMailCategory)(implicit session: RWSession): Unit
}

@Singleton
class EmailOptOutRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[EmailOptOut] with EmailOptOutRepo {

  import db.Driver.simple._

  type RepoImpl = EmailOptOutTable
  class EmailOptOutTable(tag: Tag) extends RepoTable[EmailOptOut](db, tag, "email_opt_out") {
    def address = column[EmailAddress]("address", O.NotNull)
    def category = column[ElectronicMailCategory]("category", O.NotNull)
    def * = (id.?, createdAt, updatedAt, address, category, state) <> ((EmailOptOut.apply _).tupled, EmailOptOut.unapply _)
  }

  def table(tag: Tag) = new EmailOptOutTable(tag)
  initTable()

  override def deleteCache(model: EmailOptOut)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: EmailOptOut)(implicit session: RSession): Unit = {}

  def getByEmailAddress(address: EmailAddress, excludeState: Option[State[EmailOptOut]] = Some(EmailOptOutStates.INACTIVE))(implicit session: RSession): Seq[EmailOptOut] = {
    (for (f <- rows if f.address === address && f.state =!= excludeState.orNull) yield f).list
  }

  def hasOptedOut(address: EmailAddress, category: ElectronicMailCategory = NotificationCategory.ALL)(implicit session: RSession): Boolean = {
    val all: ElectronicMailCategory = NotificationCategory.ALL
    val q = if (category == all) {
      for (f <- rows if f.address === address && f.state =!= EmailOptOutStates.INACTIVE) yield f
    } else {
      for (f <- rows if f.address === address && f.state =!= EmailOptOutStates.INACTIVE && (f.category === category || f.category === all)) yield f
    }
    q.firstOption.exists(_ => true)
  }

  def optOut(address: EmailAddress, category: ElectronicMailCategory)(implicit session: RWSession): Unit = {
    val existingRecord = rows.filter(f => f.address === address && f.category === category)
      .map(r => (r.state, r.updatedAt)).update((EmailOptOutStates.ACTIVE, clock.now())) > 0
    if (!existingRecord) {
      save(EmailOptOut(address = address, category = category))
    }
  }

  def optIn(address: EmailAddress, category: ElectronicMailCategory)(implicit session: RWSession): Unit = {
    rows.filter(f => f.address === address && f.category === category)
      .map(r => (r.state, r.updatedAt)).update((EmailOptOutStates.INACTIVE, clock.now()))
  }
}
