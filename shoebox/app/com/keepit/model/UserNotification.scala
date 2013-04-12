package com.keepit.model

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.search.Lang
import scala.slick.util.CloseableIterator
import play.api.libs.json.JsValue
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import play.libs.Json
import com.keepit.serializer.SendableNotificationSerializer
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
  def getWithUserId(userId: Id[User], lastTime: Option[DateTime], howMany: Int = 10, excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED))(implicit session: RSession): Seq[UserNotification]
  def getWithCommentId(userId: Id[User], commentId: Id[Comment], excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED))(implicit session: RSession): Option[UserNotification]
  def getWithCommentIds(userId: Id[User], commentIds: Traversable[Id[Comment]], excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED))(implicit session: RSession): Seq[UserNotification]
  def getUnreadCount(userId: Id[User])(implicit session: RSession): Int
  def getLastReadTime(userId: Id[User])(implicit session: RSession): DateTime
  def setLastReadTime(userId: Id[User])(implicit session: RWSession): DateTime
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

  override lazy val table = new RepoTable[UserNotification](db, "user_notification") with ExternalIdColumn[UserNotification] {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def category = column[UserNotificationCategory]("category", O.NotNull)
    def details = column[UserNotificationDetails]("details", O.NotNull)
    def commentId = column[Id[Comment]]("comment_id", O.Nullable)
    def subsumedId = column[Id[UserNotification]]("subsumed_id", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ externalId ~ category ~ details ~ commentId.? ~ subsumedId.? ~ state <> (UserNotification, UserNotification.unapply _)
  }

  def getWithUserId(userId: Id[User], createdBefore: Option[DateTime], howMany: Int = 10, excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED))(implicit session: RSession): Seq[UserNotification] = {
    (for(b <- table if b.userId === userId && !b.state.inSet(excludeStates) && b.createdAt <= createdBefore.getOrElse(END_OF_TIME)) yield b)
      .sortBy(_.id desc)
      .take(howMany).list
  }

  def getWithCommentId(userId: Id[User], commentId: Id[Comment], excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED))(implicit session: RSession): Option[UserNotification] =
    (for(b <- table if b.userId === userId && !b.state.inSet(excludeStates) && b.commentId === commentId) yield b).firstOption

  def getWithCommentIds(userId: Id[User], commentIds: Traversable[Id[Comment]], excludeStates: Set[State[UserNotification]] = Set(UserNotificationStates.INACTIVE, UserNotificationStates.SUBSUMED))(implicit session: RSession): Seq[UserNotification] =
    (for(b <- table if b.userId === userId && !b.state.inSet(excludeStates) && b.commentId.inSet(commentIds)) yield b).list

  def setLastReadTime(userId: Id[User])(implicit session: RWSession): DateTime =
    parseStandardTime(userValueRepo.setValue(userId, "notificationLastRead", clock.now.toStandardTimeString))

  def getLastReadTime(userId: Id[User])(implicit session: RSession): DateTime =
    userValueRepo.getValue(userId, "notificationLastRead").map(parseStandardTime).getOrElse(START_OF_TIME)

  def getUnreadCount(userId: Id[User])(implicit session: RSession): Int = {
    val lastRead = userValueRepo.getValue(userId, "notificationLastRead").map(parseStandardTime).getOrElse(START_OF_TIME)
    Query((for (b <- table if b.userId === userId && b.state === UserNotificationStates.UNDELIVERED && b.createdAt > lastRead) yield b).length).first
  }
}

object UserNotificationStates {
  val INACTIVE = State[UserNotification]("inactive")
  val UNDELIVERED = State[UserNotification]("undelivered")
  val DELIVERED = State[UserNotification]("delivered")
  val VISITED = State[UserNotification]("visited")
  val SUBSUMED = State[UserNotification]("subsumed")
}

case class UserNotificationCategory(val name: String) extends AnyVal

object UserNotificationCategories {
  val COMMENT = UserNotificationCategory("comment")
  val MESSAGE = UserNotificationCategory("message")
  val GLOBAL = UserNotificationCategory("global")
}


