package com.keepit.model

import org.joda.time.DateTime

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick._
import com.keepit.common.db.{State, Id}
import com.keepit.common.time._

@ImplementedBy(classOf[EmailAddressRepoImpl])
trait EmailAddressRepo extends Repo[EmailAddress] with RepoWithDelete[EmailAddress] with SeqNumberFunction[EmailAddress] {
  def getByAddress(address: String, excludeState: Option[State[EmailAddress]] = Some(EmailAddressStates.INACTIVE))
    (implicit session: RSession): Seq[EmailAddress]
  def getByAddressOpt(address: String, excludeState: Option[State[EmailAddress]] = Some(EmailAddressStates.INACTIVE))
      (implicit session: RSession): Option[EmailAddress]
  def getAllByUser(userId: Id[User])(implicit session: RSession): Seq[EmailAddress]
  def getByUser(userId: Id[User])(implicit session: RSession): EmailAddress
  def getByUserAndCode(userId: Id[User], verificationCode: String)(implicit session: RSession): Option[EmailAddress]
  def verify(userId: Id[User], verificationCode: String)(implicit session: RWSession): (Option[EmailAddress], Boolean) // returns (verifiedEmailOption, isFirstTimeUsed)
  def getByCode(verificationCode: String)(implicit session: RSession): Option[EmailAddress]
}

@Singleton
class EmailAddressRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock, userValueRepo: UserValueRepo, userRepo: UserRepo)
  extends DbRepo[EmailAddress] with DbRepoWithDelete[EmailAddress] with SeqNumberDbFunction[EmailAddress] with EmailAddressRepo {

  import db.Driver.simple._

  type RepoImpl = EmailAddressTable
  class EmailAddressTable(tag: Tag) extends RepoTable[EmailAddress](db, tag, "email_address") with SeqNumberColumn[EmailAddress] {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def address = column[String]("address", O.NotNull)
    def verifiedAt = column[DateTime]("verified_at", O.NotNull)
    def lastVerificationSent = column[DateTime]("last_verification_sent", O.Nullable)
    def verificationCode = column[Option[String]]("verification_code", O.Nullable)
    def * = (id.?, createdAt, updatedAt, userId, state, address, verifiedAt.?, lastVerificationSent.?,
      verificationCode, seq) <> ((EmailAddress.apply _).tupled, EmailAddress.unapply _)
  }

  def table(tag: Tag) = new EmailAddressTable(tag)
  initTable()

  private val sequence = db.getSequence[EmailAddress]("email_address_sequence")

  override def save(emailAddress: EmailAddress)(implicit session: RWSession): EmailAddress = {
    val toSave = emailAddress.copy(seq = sequence.incrementAndGet())
    super.save(toSave)
  }

  override def deleteCache(emailAddr: EmailAddress)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: EmailAddress)(implicit session: RSession): Unit = {}

  def getByAddress(address: String, excludeState: Option[State[EmailAddress]] = Some(EmailAddressStates.INACTIVE))
    (implicit session: RSession): Seq[EmailAddress] =
    (for(f <- rows if f.address === address && f.state =!= excludeState.orNull) yield f).list

  def getByAddressOpt(address: String, excludeState: Option[State[EmailAddress]] = Some(EmailAddressStates.INACTIVE))
      (implicit session: RSession): Option[EmailAddress] = {
    val allAddresses = getByAddress(address, excludeState)
    allAddresses.find(_.state == EmailAddressStates.VERIFIED).orElse(allAddresses.find(_.state == EmailAddressStates.UNVERIFIED).headOption)
  }

  def getAllByUser(userId: Id[User])(implicit session: RSession): Seq[EmailAddress] =
    (for(f <- rows if f.userId === userId && f.state =!= EmailAddressStates.INACTIVE) yield f).list

  def getByUser(userId: Id[User])(implicit session: RSession): EmailAddress = {
    if (userRepo.get(userId).primaryEmailId.isDefined) {
      get(userRepo.get(userId).primaryEmailId.get)
    } else {
      val all = getAllByUser(userId)
      all.find(_.verified) match {
        case Some(em) =>
          em
        case None =>
          all.headOption.getOrElse(throw new Exception(s"no emails for user $userId"))
      }
    }
  }

  def getByUserAndCode(userId: Id[User], verificationCode: String)(implicit session: RSession): Option[EmailAddress] =
    (for (e <- rows if e.userId === userId && e.verificationCode === verificationCode && e.state =!= EmailAddressStates.INACTIVE) yield e).firstOption

  def verify(userId: Id[User], verificationCode: String)(implicit session: RWSession): (Option[EmailAddress], Boolean) = {
    // returns (verifiedEmailOption, isFirstTimeUsed)
    getByUserAndCode(userId, verificationCode) match {
      case Some(verifiedAddress) if verifiedAddress.state == EmailAddressStates.VERIFIED => (Some(verifiedAddress), false)
      case Some(unverifiedAddress) if unverifiedAddress.state == EmailAddressStates.UNVERIFIED => (Some(save(unverifiedAddress.withState(EmailAddressStates.VERIFIED))), true)
      case None => (None, false)
    }
  }

  def getByCode(verificationCode: String)(implicit session: RSession): Option[EmailAddress] = {
    (for (e <- rows if e.verificationCode === verificationCode && e.state =!= EmailAddressStates.INACTIVE) yield e).firstOption
  }
}
