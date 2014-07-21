package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import org.joda.time.DateTime

@ImplementedBy(classOf[DelightedUserRepoImpl])
trait DelightedUserRepo extends Repo[DelightedUser] {
  def getByDelightedExtUserId(delightedExtUserId: String)(implicit session: RSession): Option[DelightedUser]
  def getByUserId(userId: Id[User])(implicit session: RSession): Option[DelightedUser]
  def getLastInteractedDateForUserId(userId: Id[User])(implicit session: RSession): Option[DateTime]
  def getLastInteractedDate(delightedUserId: Id[DelightedUser])(implicit session: RSession): Option[DateTime]
  def setLastInteractedDate(delightedUserId: Id[DelightedUser], lastAnswerDate: DateTime)(implicit session: RWSession): Unit
}

@Singleton
class DelightedUserRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock)
    extends DbRepo[DelightedUser] with DelightedUserRepo {

  import db.Driver.simple._

  type RepoImpl = DelightedUserTable
  class DelightedUserTable(tag: Tag) extends RepoTable[DelightedUser](db, tag, "delighted_user") {
    def delightedExtUserId = column[String]("delighted_ext_user_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def email = column[EmailAddress]("email", O.Nullable)
    def userLastInteracted = column[DateTime]("user_last_interacted", O.Nullable)
    def * = (id.?, createdAt, updatedAt, delightedExtUserId, userId, email.?, userLastInteracted.?) <> ((DelightedUser.apply _).tupled, DelightedUser.unapply _)
  }

  def table(tag: Tag) = new DelightedUserTable(tag)
  initTable()

  override def deleteCache(model: DelightedUser)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: DelightedUser)(implicit session: RSession): Unit = {}

  def getByDelightedExtUserId(delightedExtUserId: String)(implicit session: RSession): Option[DelightedUser] = {
    (for { u <- rows if u.delightedExtUserId === delightedExtUserId } yield u).firstOption
  }

  def getByUserId(userId: Id[User])(implicit session: RSession): Option[DelightedUser] = {
    (for { u <- rows if u.userId === userId } yield u).firstOption
  }

  def getLastInteractedDateForUserId(userId: Id[User])(implicit session: RSession): Option[DateTime] = {
    (for { u <- rows if u.userId === userId } yield u).firstOption.flatMap(_.userLastInteracted)
  }

  def getLastInteractedDate(delightedUserId: Id[DelightedUser])(implicit session: RSession): Option[DateTime] = {
    (for { u <- rows if u.id === delightedUserId } yield u).firstOption.flatMap(_.userLastInteracted)
  }

  def setLastInteractedDate(delightedUserId: Id[DelightedUser], lastAnswerDate: DateTime)(implicit session: RWSession): Unit = {
    (for { u <- rows if u.id === delightedUserId } yield (u.updatedAt, u.userLastInteracted)).update((clock.now(), lastAnswerDate))
  }
}
