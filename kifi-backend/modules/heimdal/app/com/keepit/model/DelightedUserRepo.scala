package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.db.Id
import com.keepit.common.time._

@ImplementedBy(classOf[DelightedUserRepoImpl])
trait DelightedUserRepo extends Repo[DelightedUser]

@Singleton
class DelightedUserRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock)
  extends DbRepo[DelightedUser] with DelightedUserRepo {

  import db.Driver.simple._

  type RepoImpl = DelightedUserTable
  class DelightedUserTable(tag: Tag) extends RepoTable[DelightedUser](db, tag, "delighted_user") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def * = (id.?, createdAt, updatedAt, userId) <> ((DelightedUser.apply _).tupled, DelightedUser.unapply _)
  }

  def table(tag: Tag) = new DelightedUserTable(tag)
  initTable()

  override def deleteCache(model: DelightedUser)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: DelightedUser)(implicit session: RSession): Unit = {}
}
