package com.keepit.model

import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db._
import com.keepit.common.time._
import com.google.inject.{Singleton, ImplementedBy, Inject}


@ImplementedBy(classOf[UserBookmarkClicksRepoImpl])
trait UserBookmarkClicksRepo extends Repo[UserBookmarkClicks]{
  def getByUserUri(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Option[UserBookmarkClicks]
  def increamentCounts(userId: Id[User], uriId: Id[NormalizedURI], isSelf: Boolean)(implicit session: RWSession): UserBookmarkClicks
}

@Singleton
class UserBookmarkClicksRepoImpl @Inject()(
  val db: DataBaseComponent,
  val clock: Clock
) extends DbRepo[UserBookmarkClicks] with UserBookmarkClicksRepo {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[UserBookmarkClicks](db, "user_bookmark_clicks") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def selfClicks = column[Int]("self_clicks", O.NotNull)
    def otherClicks = column[Int]("other_clicks", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ uriId ~ selfClicks ~ otherClicks <> (UserBookmarkClicks.apply _, UserBookmarkClicks.unapply _)
  }

  def getByUserUri(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Option[UserBookmarkClicks] = {
    (for( r<- table if (r.userId === userId && r.uriId === uriId) ) yield r).firstOption
  }

  def increamentCounts(userId: Id[User], uriId: Id[NormalizedURI], isSelf: Boolean)(implicit session: RWSession): UserBookmarkClicks = {
    val old = getByUserUri(userId, uriId)
    old match {
      case Some(record) => {
        val (m, n) = (record.selfClicks, record.otherClicks)
        if (isSelf) save(record.copy(selfClicks = m + 1))
        else save(record.copy(otherClicks = n + 1))
      }
      case None => {
        val newRecord = {
          if (isSelf) UserBookmarkClicks(userId = userId, uriId = uriId, selfClicks = 1, otherClicks = 0)
          else UserBookmarkClicks(userId = userId, uriId = uriId, selfClicks = 0, otherClicks = 1)
        }
        save(newRecord)
      }
    }
  }

}