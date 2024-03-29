package com.keepit.model

import com.keepit.classify.NormalizedHostname
import com.keepit.common.actor.ActorInstance
import com.keepit.common.core.optionExtensionOps
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.{ SequencingActor, SequencingPlugin, SchedulingProperties }
import com.keepit.social.{ IdentityUserIdKey, IdentityUserIdCache }
import org.joda.time.DateTime

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ DbSequenceAssigner, State, Id }
import com.keepit.common.time._
import com.keepit.common.mail.{ EmailAddressHash, EmailAddress }

import scala.concurrent.duration._

@ImplementedBy(classOf[UserEmailAddressRepoImpl])
trait UserEmailAddressRepo extends Repo[UserEmailAddress] with RepoWithDelete[UserEmailAddress] with SeqNumberFunction[UserEmailAddress] {
  def getByAddress(address: EmailAddress, excludeState: Option[State[UserEmailAddress]] = Some(UserEmailAddressStates.INACTIVE))(implicit session: RSession): Option[UserEmailAddress]
  def getByAddressAndUser(userId: Id[User], address: EmailAddress, excludeState: Option[State[UserEmailAddress]] = Some(UserEmailAddressStates.INACTIVE))(implicit session: RSession): Option[UserEmailAddress]
  def getUsersByAddresses(addresses: Set[EmailAddress], excludeState: Option[State[UserEmailAddress]] = Some(UserEmailAddressStates.INACTIVE))(implicit session: RSession): Map[EmailAddress, Id[User]]
  def getAllByUser(userId: Id[User])(implicit session: RSession): Seq[UserEmailAddress]
  def getByUser(userId: Id[User])(implicit session: RSession): EmailAddress
  def getPrimaryByUser(userId: Id[User])(implicit session: RSession): Option[UserEmailAddress]
  def getByCode(verificationCode: EmailVerificationCode)(implicit session: RSession): Option[UserEmailAddress]
  def getOwner(address: EmailAddress)(implicit session: RSession): Option[Id[User]]
  def getUnverified(from: DateTime, to: DateTime)(implicit session: RSession): Seq[UserEmailAddress]
  def getByDomain(domain: NormalizedHostname, excludeState: Option[State[UserEmailAddress]] = Some(UserEmailAddressStates.INACTIVE))(implicit session: RSession): Set[UserEmailAddress]
}

@Singleton
class UserEmailAddressRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    userRepo: UserRepo,
    userIdentityCache: IdentityUserIdCache) extends DbRepo[UserEmailAddress] with DbRepoWithDelete[UserEmailAddress] with SeqNumberDbFunction[UserEmailAddress] with UserEmailAddressRepo {

  import db.Driver.simple._

  implicit val verificationCodeColumnType = MappedColumnType.base[EmailVerificationCode, String](_.value, EmailVerificationCode.apply(_))

  type RepoImpl = UserEmailAddressTable
  class UserEmailAddressTable(tag: Tag) extends RepoTable[UserEmailAddress](db, tag, "email_address") with SeqNumberColumn[UserEmailAddress] {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def address = column[EmailAddress]("address", O.NotNull)
    def domain = column[NormalizedHostname]("domain", O.NotNull)
    def hash = column[EmailAddressHash]("hash", O.NotNull)
    def primary = column[Option[Boolean]]("primary", O.Nullable)
    def verifiedAt = column[Option[DateTime]]("verified_at", O.Nullable)
    def lastVerificationSent = column[Option[DateTime]]("last_verification_sent", O.Nullable)
    def verificationCode = column[Option[EmailVerificationCode]]("verification_code", O.Nullable)
    def * = (id.?, createdAt, updatedAt, userId, state, address, domain, hash, primary, verifiedAt, lastVerificationSent,
      verificationCode, seq) <> ((UserEmailAddress.applyFromDbRow _).tupled, UserEmailAddress.unapplyToDbRow _)
  }

  def table(tag: Tag) = new UserEmailAddressTable(tag)
  initTable()

  override def save(emailAddress: UserEmailAddress)(implicit session: RWSession): UserEmailAddress = {
    val toSave = (
      if (emailAddress.state == UserEmailAddressStates.INACTIVE) emailAddress.sanitizedForDelete
      else emailAddress
    ).copy(seq = deferredSeqNum())
    userRepo.save(userRepo.get(emailAddress.userId)) // just to bump up user seqNum
    super.save(toSave)
  }

  override def deleteCache(emailAddress: UserEmailAddress)(implicit session: RSession): Unit = {
    userIdentityCache.remove(IdentityUserIdKey(emailAddress.address))
  }
  override def invalidateCache(emailAddress: UserEmailAddress)(implicit session: RSession): Unit = {
    deleteCache(emailAddress)
  }

  def getByAddress(address: EmailAddress, excludeState: Option[State[UserEmailAddress]] = Some(UserEmailAddressStates.INACTIVE))(implicit session: RSession): Option[UserEmailAddress] = {
    val hash = EmailAddressHash.hashEmailAddress(address)
    (for (f <- rows if f.address === address && f.hash === hash && f.state =!= excludeState.orNull) yield f).firstOption
  }

  def getByAddressAndUser(userId: Id[User], address: EmailAddress, excludeState: Option[State[UserEmailAddress]] = Some(UserEmailAddressStates.INACTIVE))(implicit session: RSession): Option[UserEmailAddress] = {
    val hash = EmailAddressHash.hashEmailAddress(address)
    (for (f <- rows if f.address === address && f.hash === hash && f.userId === userId && f.state =!= excludeState.orNull) yield f).firstOption
  }

  def getUsersByAddresses(addresses: Set[EmailAddress], excludeState: Option[State[UserEmailAddress]] = Some(UserEmailAddressStates.INACTIVE))(implicit session: RSession): Map[EmailAddress, Id[User]] = {
    val hashes = addresses.map(EmailAddressHash.hashEmailAddress)
    rows.filter(row => row.hash.inSet(hashes) && row.address.inSet(addresses) && row.state =!= excludeState.orNull).list.map(x => x.address -> x.userId).toMap
  }

  def getAllByUser(userId: Id[User])(implicit session: RSession): Seq[UserEmailAddress] = {
    (for (f <- rows if f.userId === userId && f.state =!= UserEmailAddressStates.INACTIVE) yield f).list
  }

  def getByUser(userId: Id[User])(implicit session: RSession): EmailAddress = {
    val all = getAllByUser(userId)
    (all.find(_.primary) orElse all.find(_.verified) orElse all.headOption) match {
      case Some(email) => email.address
      case None => throw new Exception(s"no emails for user $userId")
    }
  }

  def getPrimaryByUser(userId: Id[User])(implicit session: RSession): Option[UserEmailAddress] = {
    getAllByUser(userId).find(_.primary)
  }

  def getByCode(verificationCode: EmailVerificationCode)(implicit session: RSession): Option[UserEmailAddress] = {
    (for (e <- rows if e.verificationCode === verificationCode && e.state =!= UserEmailAddressStates.INACTIVE) yield e).firstOption
  }

  def getOwner(address: EmailAddress)(implicit session: RSession): Option[Id[User]] = {
    getByAddress(address).map(_.userId)
  }

  def getUnverified(from: DateTime, to: DateTime)(implicit session: RSession): Seq[UserEmailAddress] = {
    (for (e <- rows if e.state =!= UserEmailAddressStates.INACTIVE && e.verifiedAt.isEmpty && e.createdAt > from && e.createdAt < to) yield e).list
  }

  def getByDomain(domain: NormalizedHostname, excludeState: Option[State[UserEmailAddress]] = Some(UserEmailAddressStates.INACTIVE))(implicit session: RSession): Set[UserEmailAddress] = {
    rows.filter(r => r.domain === domain && r.state =!= excludeState.orNull).list.toSet
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
