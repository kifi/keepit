package com.keepit.model

import scala.concurrent.duration.Duration
import scala.slick.lifted.Query

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, Key}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.serializer.TraversableFormat

@ImplementedBy(classOf[UserConnectionRepoImpl])
trait UserConnectionRepo extends Repo[UserConnection] {
  def getConnectedUsers(id: Id[User])(implicit session: RSession): Set[Id[User]]
  def getUnfriendedUsers(id: Id[User])(implicit session: RSession): Set[Id[User]]
  def getConnectionOpt(u1: Id[User], u2: Id[User])(implicit session: RSession): Option[UserConnection]
  def addConnections(userId: Id[User], users: Set[Id[User]], requested: Boolean = false)(implicit session: RWSession)
  def unfriendConnections(userId: Id[User], users: Set[Id[User]])(implicit session: RWSession): Int
  def getConnectionCount(userId: Id[User])(implicit session: RSession): Int
  def deactivateAllConnections(userId: Id[User])(implicit session: RWSession): Unit
}

case class UnfriendedConnectionsKey(userId: Id[User]) extends Key[Set[Id[User]]] {
  val namespace = "unfriended_connections"
  def toKey(): String = userId.id.toString
}

class UnfriendedConnectionsCache(inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UnfriendedConnectionsKey, Set[Id[User]]](inner, outer:_*)(TraversableFormat.set(Id.format[User]))

@Singleton
class UserConnectionRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val friendRequestRepo: FriendRequestRepo,
  val connCountCache: UserConnectionCountCache,
  val userConnCache: UserConnectionIdCache,
  val unfriendedCache: UnfriendedConnectionsCache,
  val searchFriendsCache: SearchFriendsCache)
  extends DbRepo[UserConnection] with UserConnectionRepo {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  def invalidateCache(userId: Id[User]): Unit = {
    userConnCache.remove(UserConnectionIdKey(userId))
    connCountCache.remove(UserConnectionCountKey(userId))
    searchFriendsCache.remove(SearchFriendsKey(userId))
    unfriendedCache.remove(UnfriendedConnectionsKey(userId))
  }

  override def invalidateCache(conn: UserConnection)(implicit session: RSession): UserConnection = {
    Set(conn.user1, conn.user2) foreach invalidateCache
    conn
  }

  def getConnectionCount(userId: Id[User])(implicit session: RSession): Int = {
    connCountCache.getOrElse(UserConnectionCountKey(userId)) {
      Query((for {
        c <- table if (c.user1 === userId || c.user2 === userId) && c.state === UserConnectionStates.ACTIVE
      } yield c).length).first
    }
  }

  override val table = new RepoTable[UserConnection](db, "user_connection") {
    def user1 = column[Id[User]]("user_1", O.NotNull)
    def user2 = column[Id[User]]("user_2", O.NotNull)
    def * = id.? ~ user1 ~ user2 ~ state ~ createdAt ~ updatedAt <> (UserConnection, UserConnection.unapply _)
  }

  def getConnectionOpt(u1: Id[User], u2: Id[User])(implicit session: RSession): Option[UserConnection] =
    (for (c <- table if c.user1 === u1 && c.user2 === u2 || c.user2 === u1 && c.user1 === u2) yield c).firstOption

  def getConnectedUsers(id: Id[User])(implicit session: RSession): Set[Id[User]] = {
    userConnCache.getOrElse(UserConnectionIdKey(id)){
      ((for (c <- table if c.user1 === id && c.state === UserConnectionStates.ACTIVE) yield c.user2) union
        (for (c <- table if c.user2 === id && c.state === UserConnectionStates.ACTIVE) yield c.user1)).list.toSet
    }
  }

  def getUnfriendedUsers(id: Id[User])(implicit session: RSession): Set[Id[User]] = {
    unfriendedCache.getOrElse(UnfriendedConnectionsKey(id)) {
      ((for (c <- table if c.user1 === id && c.state === UserConnectionStates.UNFRIENDED) yield c.user2) union
          (for (c <- table if c.user2 === id && c.state === UserConnectionStates.UNFRIENDED) yield c.user1)).list.toSet
    }
  }

  def deactivateAllConnections(userId: Id[User])(implicit session: RWSession): Unit = {
    val changedUserIds = (for {
      c <- table if c.user1 === userId || c.user1 === userId
    } yield (c.user1, c.user2)).list.foldLeft(Set[Id[User]]()) { case (set, (a, b)) => set + a + b }
    (for {
      c <- table if c.user1 === userId || c.user1 === userId
    } yield c.state ~ c.updatedAt).update(UserConnectionStates.INACTIVE -> clock.now())
    changedUserIds foreach invalidateCache
  }

  def unfriendConnections(userId: Id[User], users: Set[Id[User]])(implicit session: RWSession): Int = {
    val res = (for {
      c <- table if c.user2 === userId && c.user1.inSet(users) || c.user1 === userId && c.user2.inSet(users)
    } yield c.state).update(UserConnectionStates.UNFRIENDED)

    (friendRequestRepo.getBySender(userId).filter(users contains _.recipientId) ++
        friendRequestRepo.getByRecipient(userId).filter(users contains _.senderId)) map { friendRequest =>
      friendRequestRepo.save(friendRequest.copy(state = FriendRequestStates.IGNORED))
    }

    (users + userId) foreach invalidateCache
    res
  }

  def addConnections(userId: Id[User], users: Set[Id[User]], requested: Boolean = false)(implicit session: RWSession) {
    (for {
      c <- table if (c.user2 === userId && c.user1.inSet(users) || c.user1 === userId && c.user2.inSet(users)) &&
        (if (requested) c.state =!= UserConnectionStates.ACTIVE else c.state === UserConnectionStates.INACTIVE)
    } yield c.state).update(UserConnectionStates.ACTIVE)
    val toInsert = users -- {
      (for (c <- table if c.user1 === userId) yield c.user2) union
        (for (c <- table if c.user2 === userId) yield c.user1)
      }.list.toSet

    (friendRequestRepo.getBySender(userId).filter(users contains _.recipientId) ++
      friendRequestRepo.getByRecipient(userId).filter(users contains _.senderId)) map { friendRequest =>
        friendRequestRepo.save(friendRequest.copy(state = FriendRequestStates.ACCEPTED))
    }

    (users + userId) foreach invalidateCache
    table.insertAll(toInsert.map(connId => UserConnection(user1 = userId, user2 = connId)).toSeq: _*)
  }
}
