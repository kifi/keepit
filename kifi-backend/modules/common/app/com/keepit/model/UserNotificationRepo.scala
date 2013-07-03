package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import org.joda.time.DateTime
import com.keepit.common.db.{ExternalId, State, Id}
import com.keepit.common.time._
import com.keepit.common.logging.Logging
import scala.collection.mutable
import scala.slick.lifted.Query

@ImplementedBy(classOf[UserNotificationRepoImpl])
trait UserNotificationRepo extends Repo[UserNotification] with ExternalIdColumnFunction[UserNotification]  {
  def getUnvisitedNotificationsWithCommentRead()(implicit s: RSession): Seq[UserNotification]
  def allActive(category: UserNotificationCategory)(implicit session: RSession): Seq[UserNotification]
  def allUndelivered(before: DateTime)(implicit session: RSession): Seq[UserNotification]
  def getLatestFor(userId: Id[User], howMany: Int = 10, excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED))(implicit session: RSession): Seq[UserNotification]
  def getCreatedAfter(userId: Id[User], time: DateTime, excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED))(implicit session: RSession): Seq[UserNotification]
  def getCreatedBefore(userId: Id[User], time: DateTime, howMany: Int, excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED))(implicit session: RSession): Seq[UserNotification]
  def getWithCommentId(userId: Id[User], commentId: Id[Comment], excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED))(implicit session: RSession): Option[UserNotification]
  def getWithCommentIds(userId: Id[User], commentIds: Traversable[Id[Comment]], excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED))(implicit session: RSession): Seq[UserNotification]
  def getUnvisitedCount(userId: Id[User])(implicit session: RSession): Int
  def getLastReadTime(userId: Id[User])(implicit session: RSession): DateTime
  def setLastReadTime(userId: Id[User], time: DateTime)(implicit session: RWSession): DateTime
  def markVisited(userId: Id[User], noticeExternalId: ExternalId[UserNotification], excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED, UserNotificationStates.VISITED))(implicit session: RWSession): Int
  def markCommentVisited(userId: Id[User], commentIds: Traversable[Id[Comment]], excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED, UserNotificationStates.VISITED))(implicit session: RWSession): Int
}

@Singleton
class UserNotificationRepoImpl @Inject() (
                                           val db: DataBaseComponent,
                                           val clock: Clock,
                                           userValueRepo: UserValueRepo)
  extends DbRepo[UserNotification] with UserNotificationRepo with ExternalIdColumnDbFunction[UserNotification] with Logging {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[UserNotification](db, "user_notification") with ExternalIdColumn[UserNotification] {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def category = column[UserNotificationCategory]("category", O.NotNull)
    def details = column[UserNotificationDetails]("details", O.NotNull)
    def commentId = column[Id[Comment]]("comment_id", O.Nullable)
    def subsumedId = column[Id[UserNotification]]("subsumed_id", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ externalId ~ category ~ details ~ commentId.? ~ subsumedId.? ~ state <> (UserNotification, UserNotification.unapply _)
  }

  def getUnvisitedNotificationsWithCommentRead()(implicit s: RSession): Seq[UserNotification] = {
    import UserNotificationCategories._
    import UserNotificationStates._
    val idQuery =
      s"""
        |SELECT DISTINCT n.id AS id
        |FROM user_notification n, comment m, comment_read r
        |WHERE n.state NOT IN ('$SUBSUMED','$VISITED','$INACTIVE')
        |  AND n.category = '$MESSAGE'
        |  AND m.id = n.comment_id
        |  AND (m.id = r.parent_id OR m.parent = r.parent_id)
        |  AND r.user_id = n.user_id
        |  AND r.last_read_id >= n.comment_id;
      """.stripMargin
    val rs = s.getPreparedStatement(idQuery).executeQuery()
    try {
      val ids = mutable.ArrayBuffer[Id[UserNotification]]()
      while (rs.next()) { ids += Id(rs.getLong("id")) }
      ids.map(get)
    } finally rs.close()
  }

  def allActive(category: UserNotificationCategory)(implicit session: RSession): Seq[UserNotification] =
    (for (b <- table if b.state =!= UserNotificationStates.SUBSUMED && b.state =!= UserNotificationStates.INACTIVE && b.category === category) yield b).list

  def allUndelivered(before: DateTime)(implicit session: RSession): Seq[UserNotification] =
    (for (b <- table if b.state === UserNotificationStates.UNDELIVERED && b.createdAt < before) yield b).list

  def getLatestFor(userId: Id[User], howMany: Int = 10, excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED))(implicit session: RSession): Seq[UserNotification] = {
    (for (n <- table if n.userId === userId && !n.state.inSet(excludeStates)) yield n)
      .sortBy(n => (n.createdAt/*, n.id*/) desc)  // TODO: fix compile error when using id as secondary sort criterion
      .take(howMany).list
  }

  def getCreatedAfter(userId: Id[User], time: DateTime, excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED))(implicit session: RSession): Seq[UserNotification] = {
    (for (n <- table if n.userId === userId && !n.state.inSet(excludeStates) && n.createdAt > time) yield n)
      .sortBy(n => (n.createdAt/*, n.id*/) desc)  // TODO: fix compile error when using id as secondary sort criterion
      .list
  }

  def getCreatedBefore(userId: Id[User], time: DateTime, howMany: Int, excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED))(implicit session: RSession): Seq[UserNotification] = {
    (for (n <- table if n.userId === userId && !n.state.inSet(excludeStates) && n.createdAt < time) yield n)
      .sortBy(n => (n.createdAt/*, n.id*/) desc)  // TODO: fix compile error when using id as secondary sort criterion
      .take(howMany).list
  }

  def getWithCommentId(userId: Id[User], commentId: Id[Comment], excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED))(implicit session: RSession): Option[UserNotification] =
    (for(b <- table if b.userId === userId && !b.state.inSet(excludeStates) && b.commentId === commentId) yield b).firstOption

  def getWithCommentIds(userId: Id[User], commentIds: Traversable[Id[Comment]], excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED))(implicit session: RSession): Seq[UserNotification] =
    (for(b <- table if b.userId === userId && !b.state.inSet(excludeStates) && b.commentId.inSet(commentIds)) yield b).list

  def getUnvisitedCount(userId: Id[User])(implicit session: RSession): Int =
    Query((for (n <- table if n.userId === userId && n.state.inSet(Set(UserNotificationStates.UNDELIVERED, UserNotificationStates.DELIVERED))) yield n).length).first

  def setLastReadTime(userId: Id[User], time: DateTime)(implicit session: RWSession): DateTime =
    parseStandardTime(userValueRepo.setValue(userId, "notificationLastRead", time.toStandardTimeString))

  def getLastReadTime(userId: Id[User])(implicit session: RSession): DateTime =
    userValueRepo.getValue(userId, "notificationLastRead").map(parseStandardTime).getOrElse(START_OF_TIME)

  def markVisited(userId: Id[User], noticeExternalId: ExternalId[UserNotification], excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED, UserNotificationStates.VISITED))(implicit session: RWSession): Int =
    (for(n <- table if n.userId === userId && !n.state.inSet(excludeStates) && n.externalId === noticeExternalId) yield n.state ~ n.updatedAt)
      .update((UserNotificationStates.VISITED, currentDateTime))

  def markCommentVisited(userId: Id[User], commentIds: Traversable[Id[Comment]], excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED, UserNotificationStates.VISITED))(implicit session: RWSession): Int =
    (for(n <- table if n.userId === userId && !n.state.inSet(excludeStates) && n.commentId.inSet(commentIds)) yield n.state ~ n.updatedAt)
      .update((UserNotificationStates.VISITED, currentDateTime))

}
