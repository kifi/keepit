package com.keepit.model

import java.math.BigInteger
import java.security.SecureRandom

import org.joda.time.{Period, DateTime}

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick._
import com.keepit.common.db.{State, Id}
import com.keepit.common.time._

@ImplementedBy(classOf[PasswordResetRepoImpl])
trait PasswordResetRepo extends Repo[PasswordReset] {
  def getByAddressOpt(address: String, excludeState: Option[State[PasswordReset]] = Some(PasswordResetStates.INACTIVE))
      (implicit session: RSession): Option[PasswordReset]
  def getByUser(userId: Id[User])(implicit session: RSession): Seq[PasswordReset]
  def verifyByCode(verificationCode: String, clear: Boolean = false)(implicit session: RWSession): Boolean
  def saveWithVerificationCode(email: PasswordReset)(implicit session: RWSession): PasswordReset
  def getByCode(verificationCode: String)(implicit session: RSession): Option[PasswordReset]
}

@Singleton
class PasswordResetRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[PasswordReset] with PasswordResetRepo {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._
  import DBSession._

  private lazy val random: SecureRandom = new SecureRandom()
  private val EXPIRATION_TIME = new Period(0, 30, 0 , 0) // 30 minutes

  override val table = new RepoTable[PasswordReset](db, "password_reset") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def token = column[String]("token", O.NotNull)
    def usedAt = column[DateTime]("used_at", O.Nullable)
    def usedByIP = column[String]("used_by_ip", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ state ~ token ~ usedAt.? ~ usedByIP.? <> (PasswordReset, PasswordReset.unapply _)
  }

  def getByAddressOpt(address: String, excludeState: Option[State[PasswordReset]] = Some(PasswordResetStates.INACTIVE))
      (implicit session: RSession): Option[PasswordReset] =
    (for(f <- table if f.address === address && f.state =!= excludeState.orNull) yield f).firstOption

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[PasswordReset] =
    (for(f <- table if f.userId === userId && f.state =!= PasswordResetStates.INACTIVE) yield f).list

  def verifyByCode(verificationCode: String, clear: Boolean = false)(implicit session: RWSession): Boolean = {
    val q = table.filter(_.token === verificationCode)
    if (clear) q.map(_.token).update(None)
    q.map(e => e.token ~ e.updatedAt ~ e.state).update((clock.now(), clock.now(), PasswordResetStates.VERIFIED)) > 0
  }

  def getByToken(passwordResetToken: String)(implicit session: RSession): Option[PasswordReset] = {
    (for (e <- table if e.token === passwordResetToken && e.state =!= PasswordResetStates.INACTIVE) yield e).firstOption.flatMap { pr =>
      if (pr.createdAt.plus(EXPIRATION_TIME).isBefore(clock.now)) {
        None
      } else {
        Some(pr)
      }
    }
  }

  def saveWithNewToken(passwordReset: PasswordReset)(implicit session: RWSession): PasswordReset = {
    val code = new BigInteger(128, random).toString(36)
    save(passwordReset.copy(token = code))
  }
}
