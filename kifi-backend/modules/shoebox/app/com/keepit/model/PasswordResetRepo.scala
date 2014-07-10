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
import com.keepit.common.mail.EmailAddress

@ImplementedBy(classOf[PasswordResetRepoImpl])
trait PasswordResetRepo extends Repo[PasswordReset] {
  def getByUser(userId: Id[User], getPotentiallyExpired: Boolean = true)(implicit session: RSession): Seq[PasswordReset]
  def useResetToken(token: String, ip: String)(implicit session: RWSession): Boolean
  def getByToken(passwordResetToken: String)(implicit session: RSession): Option[PasswordReset]
  def tokenIsNotExpired(passwordReset: PasswordReset): Boolean
  def createNewResetToken(userId: Id[User], sentTo: EmailAddress)(implicit session: RWSession): PasswordReset
}

@Singleton
class PasswordResetRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[PasswordReset] with PasswordResetRepo {
  import DBSession._
  import db.Driver.simple._

  private val EXPIRATION_TIME = new Period(0, 30, 0, 0) // 30 minutes

  type RepoImpl = PasswordResetTable
  class PasswordResetTable(tag: Tag) extends RepoTable[PasswordReset](db, tag, "password_reset") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def token = column[String]("token", O.NotNull)
    def usedAt = column[DateTime]("used_at", O.Nullable)
    def usedByIP = column[String]("used_by_ip", O.Nullable)
    def sentTo = column[String]("sent_to", O.Nullable)
    def * = (id.?, createdAt, updatedAt, userId, state, token, usedAt.?, usedByIP.?, sentTo.?) <> ((PasswordReset.apply _).tupled, PasswordReset.unapply _)
  }

  def table(tag: Tag) = new PasswordResetTable(tag)
  initTable()

  override def deleteCache(model: PasswordReset)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: PasswordReset)(implicit session: RSession): Unit = {}

  def getByUser(userId: Id[User], getPotentiallyExpired: Boolean = true)(implicit session: RSession): Seq[PasswordReset] =
    if (getPotentiallyExpired) {
      (for (f <- rows if f.userId === userId && f.state =!= PasswordResetStates.INACTIVE) yield f).list
    } else {
      (for (f <- rows if f.userId === userId && f.state =!= PasswordResetStates.INACTIVE && f.createdAt > clock.now().minus(EXPIRATION_TIME)) yield f).list
    }

  def useResetToken(token: String, ip: String)(implicit session: RWSession): Boolean = {
    val tokenCannotBeOlderThan = clock.now().minus(EXPIRATION_TIME)
    val resetResult = rows.filter(pr => pr.token === token.toLowerCase && pr.state === PasswordResetStates.ACTIVE && pr.createdAt > tokenCannotBeOlderThan)
      .map(e => (e.usedAt, e.updatedAt, e.usedByIP, e.state))
      .update((clock.now(), clock.now(), ip.take(16), PasswordResetStates.USED)) > 0
    if (resetResult) {
      rows.filter(pr => pr.state === PasswordResetStates.ACTIVE).map(e => (e.updatedAt, e.state)).update((clock.now(), PasswordResetStates.INACTIVE))
    }
    resetResult
  }

  def getByToken(passwordResetToken: String)(implicit session: RSession): Option[PasswordReset] = {
    (for (e <- rows if e.token === passwordResetToken.toLowerCase) yield e).firstOption
  }

  def tokenIsNotExpired(passwordReset: PasswordReset): Boolean = {
    passwordReset.state == PasswordResetStates.ACTIVE && passwordReset.createdAt.plus(EXPIRATION_TIME).isAfter(clock.now)
  }

  def createNewResetToken(userId: Id[User], sentTo: EmailAddress)(implicit session: RWSession): PasswordReset = {
    saveWithNewToken(PasswordReset(userId = userId, state = PasswordResetStates.ACTIVE, token = "", sentTo = Some(sentTo.address)))
  }

  private def saveWithNewToken(passwordReset: PasswordReset)(implicit session: RWSession): PasswordReset = {
    val code = strings.humanFriendlyToken(8) // 29**7 = 500 billion combinations, must be used in 30 minutes
    save(passwordReset.copy(token = code))
  }
}
