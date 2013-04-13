package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.common.crypto._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import play.api.libs.json._
import com.keepit.common.cache._
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.serializer.UserSerializer
import com.keepit.common.logging.Logging
import scala.concurrent.duration._

case class User(
  id: Option[Id[User]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[User] = ExternalId(),
  firstName: String,
  lastName: String,
  state: State[User]
) extends ModelWithExternalId[User] {
  def withId(id: Id[User]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withName(firstName: String, lastName: String) = copy(firstName = firstName, lastName = lastName)
  def withExternalId(id: ExternalId[User]) = copy(externalId = id)
  def withState(state: State[User]) = copy(state = state)
}

@ImplementedBy(classOf[UserRepoImpl])
trait UserRepo extends Repo[User] with ExternalIdColumnFunction[User] {
}

case class UserExternalIdKey(externalId: ExternalId[User]) extends Key[User] {
  override val version = 2
  val namespace = "user_by_externalId"
  def toKey(): String = externalId.id
}
class UserExternalIdCache @Inject() (val repo: FortyTwoCachePlugin) extends FortyTwoCache[UserExternalIdKey, User] {
  val ttl = 24 hours
  def deserialize(obj: Any): User = UserSerializer.userSerializer.reads(Json.parse(obj.asInstanceOf[String]).asInstanceOf[JsObject]).get
  def serialize(user: User) = UserSerializer.userSerializer.writes(user)
}
case class UserIdKey(id: Id[User]) extends Key[User] {
  override val version = 2
  val namespace = "user_by_id"
  def toKey(): String = id.id.toString
}
class UserIdCache @Inject() (val repo: FortyTwoCachePlugin) extends FortyTwoCache[UserIdKey, User] {
  val ttl = 24 hours
  def deserialize(obj: Any): User = UserSerializer.userSerializer.reads(Json.parse(obj.asInstanceOf[String]).asInstanceOf[JsObject]).get
  def serialize(user: User) = UserSerializer.userSerializer.writes(user)
}

@Singleton
class UserRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val externalIdCache: UserExternalIdCache,
  val idCache: UserIdCache)
    extends DbRepo[User] with UserRepo with ExternalIdColumnDbFunction[User] with Logging {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[User](db, "user") with ExternalIdColumn[User] {
    def firstName = column[String]("first_name", O.NotNull)
    def lastName = column[String]("last_name", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ firstName ~ lastName ~ state <> (User, User.unapply _)
  }

  override def invalidateCache(user: User)(implicit session: RSession) = {
    externalIdCache.set(UserExternalIdKey(user.externalId), user)
    user.id match {
      case Some(id) => idCache.set(UserIdKey(id), user)
      case None =>
    }
    user
  }

  override def get(id: Id[User])(implicit session: RSession): User = {
    idCache.getOrElse(UserIdKey(id)) {
      (for(f <- table if f.id is id) yield f).first
    }
  }

  override def getOpt(id: ExternalId[User])(implicit session: RSession): Option[User] = {
    externalIdCache.getOrElseOpt(UserExternalIdKey(id)) {
      (for(f <- externalIdColumn if f.externalId === id) yield f).firstOption
    }
  }

}

object UserStates extends States[User] {
  val PENDING = State[User]("pending")
  val BLOCKED = State[User]("blocked")
}
