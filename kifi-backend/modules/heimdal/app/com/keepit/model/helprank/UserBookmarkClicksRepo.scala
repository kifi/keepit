package com.keepit.model.helprank

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.model.{ NormalizedURI, User, UserBookmarkClicks }

import com.keepit.common.db.slick.StaticQueryFixed.interpolation

@ImplementedBy(classOf[UserBookmarkClicksRepoImpl])
trait UserBookmarkClicksRepo extends Repo[UserBookmarkClicks] {
  def getByUserUri(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Option[UserBookmarkClicks]
  def increaseCounts(userId: Id[User], uriId: Id[NormalizedURI], isSelf: Boolean)(implicit session: RWSession): UserBookmarkClicks
  def getClickCounts(userId: Id[User])(implicit session: RSession): (Int, Int) // deprecated
  def getReKeepCounts(userId: Id[User])(implicit session: RSession): (Int, Int)
}

@Singleton
class UserBookmarkClicksRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[UserBookmarkClicks] with UserBookmarkClicksRepo {
  import db.Driver.simple._

  type RepoImpl = UserBookmarkClicksTable
  class UserBookmarkClicksTable(tag: Tag) extends RepoTable[UserBookmarkClicks](db, tag, "user_bookmark_clicks") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def selfClicks = column[Int]("self_clicks", O.NotNull)
    def otherClicks = column[Int]("other_clicks", O.NotNull)
    def rekeepCount = column[Int]("rekeep_count", O.NotNull)
    def rekeepTotalCount = column[Int]("rekeep_total_count", O.NotNull)
    def rekeepDegree = column[Int]("rekeep_degree", O.NotNull)
    def * = (id.?, createdAt, updatedAt, userId, uriId, selfClicks, otherClicks, rekeepCount, rekeepTotalCount, rekeepDegree) <> ((UserBookmarkClicks.apply _).tupled, UserBookmarkClicks.unapply _)
  }

  def table(tag: Tag) = new UserBookmarkClicksTable(tag)
  initTable()

  override def deleteCache(model: UserBookmarkClicks)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: UserBookmarkClicks)(implicit session: RSession): Unit = {}

  def getByUserUri(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Option[UserBookmarkClicks] = {
    (for (r <- rows if (r.userId === userId && r.uriId === uriId)) yield r).firstOption
  }

  def increaseCounts(userId: Id[User], uriId: Id[NormalizedURI], isSelf: Boolean)(implicit session: RWSession): UserBookmarkClicks = {
    val r = getByUserUri(userId, uriId).getOrElse(
      UserBookmarkClicks(userId = userId, uriId = uriId, selfClicks = 0, otherClicks = 0))

    save(if (isSelf) r.copy(selfClicks = r.selfClicks + 1) else r.copy(otherClicks = r.otherClicks + 1))
  }

  def getClickCounts(userId: Id[User])(implicit session: RSession): (Int, Int) = {
    //unique keeps, total clicks
    val uniqueKeepsClicked = (for (row <- rows if row.userId === userId && row.otherClicks > 0) yield row).length.run
    val totalClicks = (for (row <- rows if row.userId === userId) yield row.otherClicks).sum.run.getOrElse(0)
    (uniqueKeepsClicked, totalClicks)
  }

  def getReKeepCounts(userId: Id[User])(implicit session: RSession): (Int, Int) = {
    sql"select sum(rekeep_count), sum(rekeep_total_count) from user_bookmark_clicks where user_id=${userId}".as[(Int, Int)].first
  }
}
