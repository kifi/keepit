package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._

@ImplementedBy(classOf[DelightedUserRepoImpl])
trait DelightedUserRepo extends Repo[DelightedUser] {
  def getByDelightedExtUserId(delightedExtUserId: String)(implicit session: RSession): Option[DelightedUser]
  def getByUserId(userId: Id[User])(implicit session: RSession): Option[DelightedUser]
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
    def * = (id.?, createdAt, updatedAt, delightedExtUserId, userId, email.?) <> ((DelightedUser.apply _).tupled, DelightedUser.unapply _)
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
}
