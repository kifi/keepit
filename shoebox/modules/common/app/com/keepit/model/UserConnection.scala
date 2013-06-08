package com.keepit.model

import org.joda.time.DateTime
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db.Id
import com.keepit.common.db.Model
import com.keepit.common.db.State
import com.keepit.common.db.States
import com.keepit.common.db.slick.DBSession
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick.DataBaseComponent
import com.keepit.common.db.slick.DbRepo
import com.keepit.common.db.slick.FortyTwoTypeMappers
import com.keepit.common.db.slick.Repo
import com.keepit.common.time._
import com.keepit.common.time.currentDateTime
import com.keepit.common.cache.{JsonCacheImpl, Key, FortyTwoCachePlugin}
import scala.concurrent.duration._
import com.keepit.serializer.TraversableFormat

case class UserConnection(
    id: Option[Id[UserConnection]] = None,
    user1: Id[User],
    user2: Id[User],
    state: State[UserConnection] = UserConnectionStates.ACTIVE,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime
  ) extends Model[UserConnection] {
  def withId(id: Id[UserConnection]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[UserConnection]) = copy(state = state)
}

@ImplementedBy(classOf[UserConnectionRepoImpl])
trait UserConnectionRepo extends Repo[UserConnection] {
  def getConnectedUsers(id: Id[User])(implicit session: RSession): Set[Id[User]]
  def getConnectionOpt(u1: Id[User], u2: Id[User])(implicit session: RSession): Option[UserConnection]
  def addConnections(userId: Id[User], users: Set[Id[User]])(implicit session: RWSession)
  def removeConnections(userId: Id[User], users: Set[Id[User]])(implicit session: RWSession): Int
}

case class UserConnectionKey(userId: Id[User]) extends Key[Set[Id[User]]] {
  val namespace = "user_connection_key"
  def toKey(): String = userId.id.toString
}

object UserConnectionFormatter {implicit val format = Id.format[User]}
class UserConnectionIdCache @Inject() (repo: FortyTwoCachePlugin)
  extends JsonCacheImpl[UserConnectionKey, Set[Id[User]]]((repo, 7 days))(TraversableFormat.set(Id.format[User]))

@Singleton
class UserConnectionRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    val userConnCache: UserConnectionIdCache)
    extends DbRepo[UserConnection] with UserConnectionRepo {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  def invalidateCache(userId: Id[User]) = {
    userConnCache.remove(UserConnectionKey(userId))
  }

  override def invalidateCache(conn: UserConnection)(implicit session: RSession): UserConnection = {
    userConnCache.remove(UserConnectionKey(conn.user1))
    userConnCache.remove(UserConnectionKey(conn.user2))
    conn
  }

  override val table = new RepoTable[UserConnection](db, "user_connection") {
    def user1 = column[Id[User]]("user_1", O.NotNull)
    def user2 = column[Id[User]]("user_2", O.NotNull)
    def * = id.? ~ user1 ~ user2 ~ state ~ createdAt ~ updatedAt <> (UserConnection, UserConnection.unapply _)
  }

  def getConnectionOpt(u1: Id[User], u2: Id[User])(implicit session: RSession): Option[UserConnection] =
    (for (c <- table if c.user1 === u1 && c.user2 === u2 || c.user2 === u1 && c.user1 === u2) yield c).firstOption

  def getConnectedUsers(id: Id[User])(implicit session: RSession): Set[Id[User]] = userConnCache.getOrElse(UserConnectionKey(id)){
    ((for (c <- table if c.user1 === id && c.state === UserConnectionStates.ACTIVE) yield c.user2) union
        (for (c <- table if c.user2 === id && c.state === UserConnectionStates.ACTIVE) yield c.user1))
        .list.toSet
  }

  def removeConnections(userId: Id[User], users: Set[Id[User]])(implicit session: RWSession): Int = {
    (users + userId) foreach invalidateCache

    (for {
      c <- table if
        c.user2 === userId && c.user1.inSet(users) ||
        c.user1 === userId && c.user2.inSet(users)
    } yield c.state).update(UserConnectionStates.INACTIVE)
  }

  def addConnections(userId: Id[User], users: Set[Id[User]])(implicit session: RWSession) {
    (for {
      c <- table if
        c.user2 === userId && c.user1.inSet(users) ||
        c.user1 === userId && c.user2.inSet(users) &&
        c.state === UserConnectionStates.INACTIVE
    } yield c.state).update(UserConnectionStates.ACTIVE)
    val toInsert = users diff {
      ((for (c <- table if c.user1 === userId) yield c.user2) union
        (for (c <- table if c.user2 === userId) yield c.user1)).list.toSet
    }

    (users + userId) foreach invalidateCache
    table.insertAll(toInsert.map(connId => UserConnection(user1 = userId, user2 = connId)).toSeq: _*)
  }
}

object UserConnectionStates extends States[UserConnection]

