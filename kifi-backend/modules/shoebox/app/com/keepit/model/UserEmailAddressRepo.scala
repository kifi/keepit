package com.keepit.model

import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.{ SequencingActor, SequencingPlugin, SchedulingProperties }
import org.joda.time.DateTime

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ DbSequenceAssigner, State, Id }
import com.keepit.common.time._
import com.keepit.common.mail.EmailAddress

import scala.concurrent.duration._

@ImplementedBy(classOf[UserEmailAddressRepoImpl])
trait UserEmailAddressRepo extends Repo[UserEmailAddress] with RepoWithDelete[UserEmailAddress] with SeqNumberFunction[UserEmailAddress] {
  def getByAddress(address: EmailAddress, excludeState: Option[State[UserEmailAddress]] = Some(UserEmailAddressStates.INACTIVE))(implicit session: RSession): Seq[UserEmailAddress]
  def getByAddressOpt(address: EmailAddress, excludeState: Option[State[UserEmailAddress]] = Some(UserEmailAddressStates.INACTIVE))(implicit session: RSession): Option[UserEmailAddress]
  def getAllByUser(userId: Id[User])(implicit session: RSession): Seq[UserEmailAddress]
  def getByUser(userId: Id[User])(implicit session: RSession): EmailAddress
  def getByUserAndCode(userId: Id[User], verificationCode: String)(implicit session: RSession): Option[UserEmailAddress]
  def verify(userId: Id[User], verificationCode: String)(implicit session: RWSession): (Option[UserEmailAddress], Boolean) // returns (verifiedEmailOption, isFirstTimeUsed)
  def getByCode(verificationCode: String)(implicit session: RSession): Option[UserEmailAddress]
  def getVerifiedOwner(address: EmailAddress)(implicit session: RSession): Option[Id[User]]
  def getUnverified(from: DateTime, to: DateTime)(implicit session: RSession): Seq[UserEmailAddress]
}

@Singleton
class UserEmailAddressRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    userValueRepo: UserValueRepo,
    userRepo: UserRepo,
    verifiedEmailUserIdCache: VerifiedEmailUserIdCache) extends DbRepo[UserEmailAddress] with DbRepoWithDelete[UserEmailAddress] with SeqNumberDbFunction[UserEmailAddress] with UserEmailAddressRepo {

  import db.Driver.simple._

  type RepoImpl = UserEmailAddressTable
  class UserEmailAddressTable(tag: Tag) extends RepoTable[UserEmailAddress](db, tag, "email_address") with SeqNumberColumn[UserEmailAddress] {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def address = column[EmailAddress]("address", O.NotNull)
    def verifiedAt = column[DateTime]("verified_at", O.Nullable)
    def lastVerificationSent = column[DateTime]("last_verification_sent", O.Nullable)
    def verificationCode = column[Option[String]]("verification_code", O.Nullable)
    def * = (id.?, createdAt, updatedAt, userId, state, address, verifiedAt.?, lastVerificationSent.?,
      verificationCode, seq) <> ((UserEmailAddress.apply _).tupled, UserEmailAddress.unapply _)
  }

  def table(tag: Tag) = new UserEmailAddressTable(tag)
  initTable()

  override def save(emailAddress: UserEmailAddress)(implicit session: RWSession): UserEmailAddress = {
    val toSave = emailAddress.copy(seq = deferredSeqNum())
    userRepo.save(userRepo.get(emailAddress.userId)) // just to bump up user seqNum
    super.save(toSave)
  }

  override def deleteCache(emailAddress: UserEmailAddress)(implicit session: RSession): Unit = {
    verifiedEmailUserIdCache.remove(VerifiedEmailUserIdKey(emailAddress.address))
  }
  override def invalidateCache(emailAddress: UserEmailAddress)(implicit session: RSession): Unit = {
    deleteCache(emailAddress)
  }

  def getByAddress(address: EmailAddress, excludeState: Option[State[UserEmailAddress]] = Some(UserEmailAddressStates.INACTIVE))(implicit session: RSession): Seq[UserEmailAddress] =
    (for (f <- rows if f.address === address && f.state =!= excludeState.orNull) yield f).list

  def getByAddressOpt(address: EmailAddress, excludeState: Option[State[UserEmailAddress]] = Some(UserEmailAddressStates.INACTIVE))(implicit session: RSession): Option[UserEmailAddress] = {
    val allAddresses = getByAddress(address, excludeState)
    allAddresses.find(_.state == UserEmailAddressStates.VERIFIED).orElse(allAddresses.find(_.state == UserEmailAddressStates.UNVERIFIED).headOption)
  }

  def getAllByUser(userId: Id[User])(implicit session: RSession): Seq[UserEmailAddress] =
    (for (f <- rows if f.userId === userId && f.state =!= UserEmailAddressStates.INACTIVE) yield f).list

  def getByUser(userId: Id[User])(implicit session: RSession): EmailAddress = {
    val user = userRepo.get(userId)
    user.primaryEmail getOrElse {
      val all = getAllByUser(userId)
      all.find(_.verified) match {
        case Some(verifiedEmail) => verifiedEmail.address
        case None => all.headOption.getOrElse(throw new Exception(s"no emails for user $userId")).address
      }
    }
  }

  def getByUserAndCode(userId: Id[User], verificationCode: String)(implicit session: RSession): Option[UserEmailAddress] =
    (for (e <- rows if e.userId === userId && e.verificationCode === verificationCode && e.state =!= UserEmailAddressStates.INACTIVE) yield e).firstOption

  def verify(userId: Id[User], verificationCode: String)(implicit session: RWSession): (Option[UserEmailAddress], Boolean) = {
    // returns (verifiedEmailOption, isFirstTimeUsed)
    getByUserAndCode(userId, verificationCode) match {
      case Some(verifiedAddress) if verifiedAddress.state == UserEmailAddressStates.VERIFIED => (Some(verifiedAddress), false)
      case Some(unverifiedAddress) if unverifiedAddress.state == UserEmailAddressStates.UNVERIFIED => (Some(save(unverifiedAddress.withState(UserEmailAddressStates.VERIFIED))), true)
      case None => (None, false)
    }
  }

  def getByCode(verificationCode: String)(implicit session: RSession): Option[UserEmailAddress] = {
    (for (e <- rows if e.verificationCode === verificationCode && e.state =!= UserEmailAddressStates.INACTIVE) yield e).firstOption
  }

  def getVerifiedOwner(address: EmailAddress)(implicit session: RSession): Option[Id[User]] = {
    getByAddress(address).find(_.verified).map(_.userId)
  }

  def getUnverified(from: DateTime, to: DateTime)(implicit session: RSession): Seq[UserEmailAddress] = {
    (for (e <- rows if e.state === UserEmailAddressStates.UNVERIFIED && e.createdAt > from && e.createdAt < to) yield e).list
  }

}

trait UserEmailAddressSeqPlugin extends SequencingPlugin

class UserEmailAddressSeqPluginImpl @Inject() (
    override val actor: ActorInstance[UserEmailAddressSeqActor],
    override val scheduling: SchedulingProperties) extends UserEmailAddressSeqPlugin {

  override val interval: FiniteDuration = 20.seconds
}

@Singleton
class UserEmailAddressSeqAssigner @Inject() (db: Database, repo: UserEmailAddressRepo, airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[UserEmailAddress](db, repo, airbrake)

class UserEmailAddressSeqActor @Inject() (assigner: UserEmailAddressSeqAssigner, airbrake: AirbrakeNotifier)
  extends SequencingActor(assigner, airbrake)
