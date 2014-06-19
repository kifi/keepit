package com.keepit.model

import org.joda.time.DateTime

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick._
import com.keepit.common.db.{State, Id}
import com.keepit.common.time._
import com.keepit.common.mail.EmailAddress

@ImplementedBy(classOf[UserEmailAddressRepoImpl])
trait UserEmailAddressRepo extends Repo[UserEmailAddress] with RepoWithDelete[UserEmailAddress] with SeqNumberFunction[UserEmailAddress] {
  def getByAddress(address: EmailAddress, excludeState: Option[State[UserEmailAddress]] = Some(UserEmailAddressStates.INACTIVE))
    (implicit session: RSession): Seq[UserEmailAddress]
  def getByAddressOpt(address: EmailAddress, excludeState: Option[State[UserEmailAddress]] = Some(UserEmailAddressStates.INACTIVE))
      (implicit session: RSession): Option[UserEmailAddress]
  def getAllByUser(userId: Id[User])(implicit session: RSession): Seq[UserEmailAddress]
  def getByUser(userId: Id[User])(implicit session: RSession): EmailAddress
  def getByUserAndCode(userId: Id[User], verificationCode: String)(implicit session: RSession): Option[UserEmailAddress]
  def verify(userId: Id[User], verificationCode: String)(implicit session: RWSession): (Option[UserEmailAddress], Boolean) // returns (verifiedEmailOption, isFirstTimeUsed)
  def getByCode(verificationCode: String)(implicit session: RSession): Option[UserEmailAddress]
  def getVerifiedOwner(address: EmailAddress)(implicit session: RSession): Option[Id[User]]
}

@Singleton
class UserEmailAddressRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  userValueRepo: UserValueRepo,
  userRepo: UserRepo,
  verifiedEmailUserIdCache: VerifiedEmailUserIdCache,
  override protected val changeListener: Option[RepoModification.Listener[UserEmailAddress]]
) extends DbRepo[UserEmailAddress] with DbRepoWithDelete[UserEmailAddress] with SeqNumberDbFunction[UserEmailAddress] with UserEmailAddressRepo {

  import db.Driver.simple._

  type RepoImpl = UserEmailAddressTable
  class UserEmailAddressTable(tag: Tag) extends RepoTable[UserEmailAddress](db, tag, "email_address") with SeqNumberColumn[UserEmailAddress] {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def address = column[EmailAddress]("address", O.NotNull)
    def verifiedAt = column[DateTime]("verified_at", O.NotNull)
    def lastVerificationSent = column[DateTime]("last_verification_sent", O.Nullable)
    def verificationCode = column[Option[String]]("verification_code", O.Nullable)
    def * = (id.?, createdAt, updatedAt, userId, state, address, verifiedAt.?, lastVerificationSent.?,
      verificationCode, seq) <> ((UserEmailAddress.apply _).tupled, UserEmailAddress.unapply _)
  }

  def table(tag: Tag) = new UserEmailAddressTable(tag)
  initTable()

  private val sequence = db.getSequence[UserEmailAddress]("email_address_sequence")

  override def save(emailAddress: UserEmailAddress)(implicit session: RWSession): UserEmailAddress = {
    val toSave = emailAddress.copy(seq = sequence.incrementAndGet())
    super.save(toSave)
  }

  override def deleteCache(emailAddress: UserEmailAddress)(implicit session: RSession): Unit = {
    if (emailAddress.verified) {
      verifiedEmailUserIdCache.remove(VerifiedEmailUserIdKey(emailAddress.address))
    }
  }
  override def invalidateCache(emailAddress: UserEmailAddress)(implicit session: RSession): Unit = {
    if (emailAddress.verified) {
      verifiedEmailUserIdCache.set(VerifiedEmailUserIdKey(emailAddress.address), emailAddress.userId)
    }
  }

  def getByAddress(address: EmailAddress, excludeState: Option[State[UserEmailAddress]] = Some(UserEmailAddressStates.INACTIVE))
    (implicit session: RSession): Seq[UserEmailAddress] =
    (for(f <- rows if f.address === address && f.state =!= excludeState.orNull) yield f).list

  def getByAddressOpt(address: EmailAddress, excludeState: Option[State[UserEmailAddress]] = Some(UserEmailAddressStates.INACTIVE))
      (implicit session: RSession): Option[UserEmailAddress] = {
    val allAddresses = getByAddress(address, excludeState)
    allAddresses.find(_.state == UserEmailAddressStates.VERIFIED).orElse(allAddresses.find(_.state == UserEmailAddressStates.UNVERIFIED).headOption)
  }

  def getAllByUser(userId: Id[User])(implicit session: RSession): Seq[UserEmailAddress] =
    (for(f <- rows if f.userId === userId && f.state =!= UserEmailAddressStates.INACTIVE) yield f).list

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
}
