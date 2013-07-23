package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock

@ImplementedBy(classOf[UserConnectionRepoImpl])
trait UserConnectionRepo extends Repo[UserConnection] {
  def getConnectedUsers(id: Id[User])(implicit session: RSession): Set[Id[User]]
  def getConnectionOpt(u1: Id[User], u2: Id[User])(implicit session: RSession): Option[UserConnection]
  def addConnections(userId: Id[User], users: Set[Id[User]], requested: Boolean = false)(implicit session: RWSession)
  def unfriendConnections(userId: Id[User], users: Set[Id[User]])(implicit session: RWSession): Int
}

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
      (for (c <- table if c.user2 === id && c.state === UserConnectionStates.ACTIVE) yield c.user1)).list.toSet
  }

  def unfriendConnections(userId: Id[User], users: Set[Id[User]])(implicit session: RWSession): Int = {
    val res = (for {
      c <- table if c.user2 === userId && c.user1.inSet(users) || c.user1 === userId && c.user2.inSet(users)
    } yield c.state).update(UserConnectionStates.UNFRIENDED)

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

    (users + userId) foreach invalidateCache
    table.insertAll(toInsert.map(connId => UserConnection(user1 = userId, user2 = connId)).toSeq: _*)
  }
}
