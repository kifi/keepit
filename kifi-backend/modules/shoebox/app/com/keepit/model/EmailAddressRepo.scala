package com.keepit.model

import org.joda.time.DateTime

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick._
import com.keepit.common.db.{State, Id}
import com.keepit.common.time._

@ImplementedBy(classOf[EmailAddressRepoImpl])
trait EmailAddressRepo extends Repo[EmailAddress] {
  def getByAddressOpt(address: String, excludeState: Option[State[EmailAddress]] = Some(EmailAddressStates.INACTIVE))
      (implicit session: RSession): Option[EmailAddress]
  def getByUser(userId: Id[User])(implicit session: RSession): Seq[EmailAddress]
  def getByUserAndCode(userId: Id[User], verificationCode: String)(implicit session: RSession): Option[EmailAddress]
  def verify(userId: Id[User], verificationCode: String)(implicit session: RWSession): Boolean
}

@Singleton
class EmailAddressRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[EmailAddress] with EmailAddressRepo {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[EmailAddress](db, "email_address") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def address = column[String]("address", O.NotNull)
    def verifiedAt = column[DateTime]("verified_at", O.NotNull)
    def lastVerificationSent = column[DateTime]("last_verification_sent", O.Nullable)
    def verificationCode = column[Option[String]]("verification_code", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ state ~ address ~ verifiedAt.? ~ lastVerificationSent.? ~
        verificationCode <> (EmailAddress, EmailAddress.unapply _)
  }

  def getByAddressOpt(address: String, excludeState: Option[State[EmailAddress]] = Some(EmailAddressStates.INACTIVE))
      (implicit session: RSession): Option[EmailAddress] =
    (for(f <- table if f.address === address && f.state =!= excludeState.orNull) yield f).firstOption

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[EmailAddress] =
    (for(f <- table if f.userId === userId && f.state =!= EmailAddressStates.INACTIVE) yield f).list

  def getByUserAndCode(userId: Id[User], verificationCode: String)(implicit session: RSession): Option[EmailAddress] =
    (for (e <- table if e.userId === userId && e.verificationCode === verificationCode && e.state =!= EmailAddressStates.INACTIVE) yield e).firstOption

  def verify(userId: Id[User], verificationCode: String)(implicit session: RWSession): Boolean = {
    val now = clock.now()
    table.filter(e => e.userId === userId && e.verificationCode === verificationCode && e.state === EmailAddressStates.UNVERIFIED)
      .map(e => e.verifiedAt ~ e.updatedAt ~ e.state).update((now, now, EmailAddressStates.VERIFIED)) match {
      case count if count > 0 => true
      case _ => getByUserAndCode(userId, verificationCode).isDefined
    }
  }

  def getByCode(verificationCode: String)(implicit session: RSession): Option[EmailAddress] = {
    (for (e <- table if e.verificationCode === verificationCode && e.state =!= EmailAddressStates.INACTIVE) yield e).firstOption
  }
}
