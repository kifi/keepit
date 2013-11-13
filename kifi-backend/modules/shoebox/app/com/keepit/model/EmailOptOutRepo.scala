package com.keepit.model

import java.math.BigInteger
import java.security.SecureRandom

import org.joda.time.{Period, DateTime}

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick._
import com.keepit.common.db.{State, Id}
import com.keepit.common.time._
import com.keepit.common.strings
import com.keepit.common.mail.{ElectronicMailCategory, PostOffice, EmailAddressHolder}
import com.keepit.common.mail.ElectronicMailCategory

@ImplementedBy(classOf[EmailOptOutRepoImpl])
trait EmailOptOutRepo extends Repo[EmailOptOut] {
  def getByEmailAddress(address: EmailAddressHolder)(implicit session: RSession): Seq[EmailOptOut]
  def hasOptedOut(address: EmailAddressHolder, category: ElectronicMailCategory = PostOffice.Categories.ALL)(implicit session: RSession): Boolean
  def optOut(address: EmailAddressHolder, category: ElectronicMailCategory)(implicit session: RWSession): Unit
  def optIn(address: EmailAddressHolder, category: ElectronicMailCategory)(implicit session: RWSession): Unit
}

@Singleton
class EmailOptOutRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[EmailOptOut] with EmailOptOutRepo {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[EmailOptOut](db, "email_opt_out") {
    def address = column[EmailAddressHolder]("address", O.NotNull)
    def category = column[ElectronicMailCategory]("category", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ address ~ category ~ state <> (EmailOptOut, EmailOptOut.unapply _)
  }

  def getByEmailAddress(address: EmailAddressHolder)(implicit session: RSession): Seq[EmailOptOut] = {
    (for(f <- table if f.address === address) yield f).list
  }

  def hasOptedOut(address: EmailAddressHolder, category: ElectronicMailCategory = PostOffice.Categories.ALL)(implicit session: RSession): Boolean = {
    val q = if (category == PostOffice.Categories.ALL) {
      for(f <- table if f.address === address && f.state =!= EmailOptOutStates.INACTIVE) yield f
    } else {
      for(f <- table if f.address === address && f.state =!= EmailOptOutStates.INACTIVE && (f.category === category || f.category === PostOffice.Categories.ALL)) yield f
    }
    q.firstOption.exists(_ => true)
  }

  def optOut(address: EmailAddressHolder, category: ElectronicMailCategory)(implicit session: RWSession): Unit = {
    val existingRecord = table.filter(f => f.address === address && f.category === category)
      .map(r => r.state ~ r.updatedAt).update((EmailOptOutStates.ACTIVE, clock.now())) > 0
    if (!existingRecord) {
      save(EmailOptOut(address = address, category = category))
    }
  }

  def optIn(address: EmailAddressHolder, category: ElectronicMailCategory)(implicit session: RWSession): Unit = {
    table.filter(f => f.address === address && f.category === category)
      .map(r => r.state ~ r.updatedAt).update((EmailOptOutStates.INACTIVE, clock.now()))
  }
}
