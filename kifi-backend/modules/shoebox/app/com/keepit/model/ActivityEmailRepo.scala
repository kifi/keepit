package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.{ Id, States, State }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.time._

@ImplementedBy(classOf[ActivityEmailRepoImpl])
trait ActivityEmailRepo extends Repo[ActivityEmail] {
  def getLatestToUser(userId: Id[User], excludeState: Option[State[ActivityEmail]] = None)(implicit session: RSession): Seq[ActivityEmail]
}

@Singleton
class ActivityEmailRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[ActivityEmail] with ActivityEmailRepo {

  import db.Driver.simple._

  type RepoImpl = ActivityEmailTable

  class ActivityEmailTable(tag: Tag) extends RepoTable[ActivityEmail](db, tag, "activity_email") {
    def userId = column[Id[User]]("user_id", O.NotNull)

    def otherFollowedLibraries = column[Option[Seq[Id[Library]]]]("content_other_followed_libraries", O.Nullable)

    def userFollowedLibraries = column[Option[Seq[Id[Library]]]]("content_user_followed_libraries", O.Nullable)

    def libraryRecommendations = column[Option[Seq[Id[Library]]]]("content_library_recommendations", O.Nullable)

    def * = (id.?, createdAt, updatedAt, state, userId, otherFollowedLibraries, userFollowedLibraries, libraryRecommendations) <> ((ActivityEmail.apply _).tupled, ActivityEmail.unapply)
  }

  def table(tag: Tag) = new ActivityEmailTable(tag)

  initTable()

  override def invalidateCache(model: ActivityEmail)(implicit session: RSession): Unit = {}

  override def deleteCache(model: ActivityEmail)(implicit session: RSession): Unit = {}

  def getLatestToUser(userId: Id[User], excludeState: Option[State[ActivityEmail]])(implicit session: RSession): Seq[ActivityEmail] =
    (for (row <- rows if row.userId === userId && row.state =!= excludeState.orNull) yield row).sortBy(_.createdAt desc).list

}

object ActivityEmailStates extends States[ActivityEmail] {
  val PENDING = State[ActivityEmail]("pending")
  val SENT = State[ActivityEmail]("sent")
  val BOUNCED = State[ActivityEmail]("bounced")
}
