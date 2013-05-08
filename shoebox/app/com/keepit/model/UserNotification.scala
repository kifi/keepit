package com.keepit.model

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.json.JsValue
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import scala.slick.lifted.Query

case class UserNotificationDetails(payload: JsValue) extends AnyVal

case class UserNotification(
  id: Option[Id[UserNotification]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  externalId: ExternalId[UserNotification] = ExternalId[UserNotification](),
  category: UserNotificationCategory,
  details: UserNotificationDetails,
  commentId: Option[Id[Comment]],
  subsumedId: Option[Id[UserNotification]],
  state: State[UserNotification] = UserNotificationStates.UNDELIVERED) extends ModelWithExternalId[UserNotification] {
  def withId(id: Id[UserNotification]): UserNotification = copy(id = Some(id))
  def withUpdateTime(now: DateTime): UserNotification = this.copy(updatedAt = now)
  def isActive: Boolean = state != UserNotificationStates.INACTIVE
  def withState(state: State[UserNotification]): UserNotification = copy(state = state)
}

@ImplementedBy(classOf[UserNotificationRepoImpl])
trait UserNotificationRepo extends Repo[UserNotification] with ExternalIdColumnFunction[UserNotification]  {
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
  def markVisited(userId: Id[User], commentIds: Traversable[Id[Comment]], excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED, UserNotificationStates.VISITED))(implicit session: RWSession): Int
}

@Singleton
class UserNotificationRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  userValueRepo: UserValueRepo)
    extends DbRepo[UserNotification] with UserNotificationRepo with ExternalIdColumnDbFunction[UserNotification] with Logging {
  import db.Driver.Implicit._
  import DBSession._
  import FortyTwoTypeMappers._

  override val table = new RepoTable[UserNotification](db, "user_notification") with ExternalIdColumn[UserNotification] {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def category = column[UserNotificationCategory]("category", O.NotNull)
    def details = column[UserNotificationDetails]("details", O.NotNull)
    def commentId = column[Id[Comment]]("comment_id", O.Nullable)
    def subsumedId = column[Id[UserNotification]]("subsumed_id", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ externalId ~ category ~ details ~ commentId.? ~ subsumedId.? ~ state <> (UserNotification, UserNotification.unapply _)
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

  def markVisited(userId: Id[User], commentIds: Traversable[Id[Comment]], excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED, UserNotificationStates.VISITED))(implicit session: RWSession): Int =
    (for(n <- table if n.userId === userId && !n.state.inSet(excludeStates) && n.commentId.inSet(commentIds)) yield n.state ~ n.updatedAt)
      .update((UserNotificationStates.VISITED, currentDateTime))
}

object UserNotificationStates {
  val INACTIVE = State[UserNotification]("inactive")
  val UNDELIVERED = State[UserNotification]("undelivered")
  val DELIVERED = State[UserNotification]("delivered")
  val VISITED = State[UserNotification]("visited")
  val SUBSUMED = State[UserNotification]("subsumed")
}

case class UserNotificationCategory(name: String) extends AnyVal

object UserNotificationCategories {
  val COMMENT = UserNotificationCategory("comment")
  val MESSAGE = UserNotificationCategory("message")
  val GLOBAL = UserNotificationCategory("global")
}


