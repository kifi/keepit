package com.keepit.model

import java.math.BigInteger
import java.security.SecureRandom

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
  def getAllByUser(userId: Id[User])(implicit session: RSession): Seq[EmailAddress]
  def verifyByCode(verificationCode: String, clear: Boolean = false)(implicit session: RWSession): Boolean
  def saveWithVerificationCode(email: EmailAddress)(implicit session: RWSession): EmailAddress
  def getByCode(verificationCode: String)(implicit session: RSession): Option[EmailAddress]
}

@Singleton
class EmailAddressRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val userRepo: UserRepo) extends DbRepo[EmailAddress] with EmailAddressRepo {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._
  import DBSession._

  private lazy val random: SecureRandom = new SecureRandom()

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

  def getAllByUser(userId: Id[User])(implicit session: RSession): Seq[EmailAddress] =
    (for(f <- table if f.userId === userId && f.state =!= EmailAddressStates.INACTIVE) yield f).list

  def getPrimaryByUser(userId: Id[User])(implicit session: RSession): Option[EmailAddress] = {
    userRepo.get(userId)
    (for (f <- table if f.userId === userId) yield f).firstOption
  }


  def verifyByCode(verificationCode: String, clear: Boolean = false)(implicit session: RWSession): Boolean = {
    val q = table.filter(_.verificationCode === verificationCode)
    if (clear) q.map(_.verificationCode).update(None)
    q.map(e => e.verifiedAt ~ e.updatedAt ~ e.state).update((clock.now(), clock.now(), EmailAddressStates.VERIFIED)) > 0
  }

  def getByCode(verificationCode: String)(implicit session: RSession): Option[EmailAddress] = {
    (for (e <- table if e.verificationCode === verificationCode && e.state =!= EmailAddressStates.INACTIVE) yield e).firstOption
  }

  def saveWithVerificationCode(email: EmailAddress)(implicit session: RWSession): EmailAddress = {
    val code = new BigInteger(128, random).toString(36)
    save(email.copy(verificationCode = Some(code)))
  }
}
