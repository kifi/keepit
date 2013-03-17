package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import java.sql.Connection
import org.joda.time.DateTime
import com.keepit.common.mail.EmailAddressHolder

case class EmailAddress (
  id: Option[Id[EmailAddress]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  state: State[EmailAddress] = EmailAddressStates.UNVERIFIED,
  address: String,
  verifiedAt: Option[DateTime] = None,
  lastVerificationSent: Option[DateTime] = None
) extends Model[EmailAddress] with EmailAddressHolder {
  def withId(id: Id[EmailAddress]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def sameAddress(otherAddress: String) = otherAddress == address
  def withState(state: State[EmailAddress]) = copy(state = state)
}

@ImplementedBy(classOf[EmailAddressRepoImpl])
trait EmailAddressRepo extends Repo[EmailAddress] {
  def getByAddressOpt(address: String)(implicit session: RSession): Option[EmailAddress]
  def getByUser(userId: Id[User])(implicit session: RSession): Seq[EmailAddress]
}

@Singleton
class EmailAddressRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[EmailAddress] with EmailAddressRepo {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[EmailAddress](db, "email_address") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def address = column[String]("address", O.NotNull)
    def verifiedAt = column[DateTime]("verified_at", O.NotNull)
    def lastVerificationSent = column[DateTime]("last_verification_sent", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ state ~ address ~ verifiedAt.? ~ lastVerificationSent.? <> (EmailAddress, EmailAddress.unapply _)
  }

  def getByAddressOpt(address: String)(implicit session: RSession): Option[EmailAddress] =
    (for(f <- table if f.address === address && f.state =!= EmailAddressStates.INACTIVE) yield f).firstOption

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[EmailAddress] =
    (for(f <- table if f.userId === userId && f.state =!= EmailAddressStates.INACTIVE) yield f).list
}

object EmailAddressStates {
  val VERIFIED = State[EmailAddress]("verified")
  val UNVERIFIED = State[EmailAddress]("unverified")
  val INACTIVE = State[EmailAddress]("inactive")
}
