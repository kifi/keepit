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
import com.keepit.realtime.UserNotificationStreamManager
import com.keepit.serializer.SendableNotificationSerializer

case class UserNotificationDetails(payload: JsValue)

case class UserNotification(
  id: Option[Id[UserNotification]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  externalId: ExternalId[UserNotification] = ExternalId[UserNotification](),
  category: UserNotificationCategory,
  details: UserNotificationDetails,
  commentId: Option[Id[Comment]],
  state: State[UserNotification] = UserNotificationStates.UNDELIVERED) extends ModelWithExternalId[UserNotification] {
  def withId(id: Id[UserNotification]): UserNotification = copy(id = Some(id))
  def withUpdateTime(now: DateTime): UserNotification = this.copy(updatedAt = now)
  def isActive: Boolean = state != UserNotificationStates.INACTIVE
  def withState(state: State[UserNotification]): UserNotification = copy(state = state)
}

@ImplementedBy(classOf[UserNotificationRepoImpl])
trait UserNotificationRepo extends Repo[UserNotification] with ExternalIdColumnFunction[UserNotification]  {
  def getWithUserId(userId: Id[User], startingTime: DateTime, howMany: Option[Int], excludeState: Option[State[UserNotification]])(implicit session: RSession): Seq[UserNotification]
  def getWithCommentId(userId: Id[User], commentId: Id[Comment])(implicit session: RSession): Seq[UserNotification]
}

@Singleton
class UserNotificationRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[UserNotification] with UserNotificationRepo with ExternalIdColumnDbFunction[UserNotification] with Logging {
  import db.Driver.Implicit._
  import DBSession._
  import FortyTwoTypeMappers._

  override lazy val table = new RepoTable[UserNotification](db, "user_notification") with ExternalIdColumn[UserNotification] {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def category = column[UserNotificationCategory]("category", O.NotNull)
    def details = column[UserNotificationDetails]("details", O.NotNull)
    def commentId = column[Id[Comment]]("comment_id", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ externalId ~ category ~ details ~ commentId.? ~ state <> (UserNotification, UserNotification.unapply _)
  }

  def getWithUserId(userId: Id[User], startingTime: DateTime, howMany: Option[Int], excludeState: Option[State[UserNotification]] = Some(UserNotificationStates.INACTIVE))(implicit session: RSession): Seq[UserNotification] = {
    val q = (for(b <- table if b.userId === userId && b.state =!= UserNotificationStates.INACTIVE && b.createdAt > startingTime) yield b)
    (howMany match {
      case Some(i) => q.take(i)
      case None => q
    }).list
  }

  def getWithCommentId(userId: Id[User], commentId: Id[Comment])(implicit session: RSession): Seq[UserNotification] =
    (for(b <- table if b.userId === userId && b.state =!= UserNotificationStates.INACTIVE && b.commentId === commentId) yield b).list
}

object UserNotificationStates {
  val INACTIVE = State[UserNotification]("inactive")
  val UNDELIVERED = State[UserNotification]("undelivered")
  val DELIVERED = State[UserNotification]("delivered")
  val VISITED = State[UserNotification]("visited")
}

case class UserNotificationCategory(val name: String) extends AnyVal

object UserNotificationCategories {
  val COMMENT = UserNotificationCategory("comment")
  val MESSAGE = UserNotificationCategory("message")
  val GLOBAL = UserNotificationCategory("global")
}


