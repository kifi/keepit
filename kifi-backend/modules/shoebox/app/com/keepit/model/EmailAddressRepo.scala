package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.Id
import com.keepit.common.time.Clock
import org.joda.time.DateTime


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

  override val table = new RepoTable[EmailAddress](db, "email_address") {
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