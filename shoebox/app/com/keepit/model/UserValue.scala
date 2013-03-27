package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.common.crypto._
import com.keepit.inject._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import play.api.Play.current
import java.net.URI
import java.security.MessageDigest
import scala.collection.mutable
import play.api.libs.json._
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.cache._
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._

case class UserValue(
  id: Option[Id[UserValue]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  key: String,
  value: LargeString,
  state: State[UserValue] = UserValueStates.ACTIVE
) extends Model[UserValue] {

  def withId(id: Id[UserValue]) = this.copy(id = Some(id))
  def withState(newState: State[UserValue]) = this.copy(state = newState)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

@ImplementedBy(classOf[UserValueRepoImpl])
trait UserValueRepo extends Repo[UserValue] {
  def getValue(userId: Id[User], key: String)(implicit session: RSession): Option[String]
}

case class UserValueKey(userId: Id[User], key: String) extends Key[String] {
  val namespace = "uservalue"
  def toKey(): String = userId.id + "_" + key
}
class UserValueCache @Inject() (val repo: FortyTwoCachePlugin) extends FortyTwoCache[UserValueKey, String] {
  val ttl = 0 seconds
  def deserialize(obj: Any): String = obj.asInstanceOf[String]
  def serialize(value: String) = value
}


@Singleton
class UserValueRepoImpl @Inject() (
  val db: DataBaseComponent,
  val valueCache: UserValueCache,
  val clock: Clock)
    extends DbRepo[UserValue] with UserValueRepo {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._
  import scala.util.matching.Regex

  override lazy val table = new RepoTable[UserValue](db, "user_value") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def value = column[LargeString]("value", O.NotNull)
    def key = column[String]("user_key", O.NotNull)

    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ key ~ value ~ state <> (UserValue, UserValue.unapply _)
  }

  override def invalidateCache(userValue: UserValue)(implicit session: RSession) = {
    valueCache.remove(UserValueKey(userValue.userId, userValue.key))
    userValue
  }

  def getValue(userId: Id[User], key: String)(implicit session: RSession): Option[String] = {
    (for(f <- table if f.state === UserValueStates.ACTIVE && f.userId === userId && f.key === key) yield f.value).firstOption.map(_.value)
  }

}

object UserValueStates extends States[UserValue]
