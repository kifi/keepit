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
import com.keepit.realtime.UserChannel
import play.api.libs.json.Json
import com.keepit.common.social.BasicUserRepo
import com.keepit.serializer.BasicUserSerializer.basicUserSerializer

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

@Singleton
class UserConnectionRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    userChannel: UserChannel,
    basicUserRepo: BasicUserRepo)
    extends DbRepo[UserConnection] with UserConnectionRepo {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[UserConnection](db, "user_connection") {
    def user1 = column[Id[User]]("user_1", O.NotNull)
    def user2 = column[Id[User]]("user_2", O.NotNull)
    def * = id.? ~ user1 ~ user2 ~ state ~ createdAt ~ updatedAt <> (UserConnection, UserConnection.unapply _)
  }

  def getConnectionOpt(u1: Id[User], u2: Id[User])(implicit session: RSession): Option[UserConnection] =
    (for (c <- table if c.user1 === u1 && c.user2 === u2 || c.user2 === u1 && c.user1 === u2) yield c).firstOption

  def getConnectedUsers(id: Id[User])(implicit session: RSession): Set[Id[User]] =
    ((for (c <- table if c.user1 === id && c.state === UserConnectionStates.ACTIVE) yield c.user2) union
        (for (c <- table if c.user2 === id && c.state === UserConnectionStates.ACTIVE) yield c.user1))
        .list.toSet

  def removeConnections(userId: Id[User], users: Set[Id[User]])(implicit session: RWSession): Int = {
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
    if(toInsert.nonEmpty) userChannel.push(userId, Json.arr("new_friends", toInsert.map(basicUserRepo.load)))
    toInsert.foreach { connId =>
      userChannel.push(connId, Json.arr("new_friends", Set(basicUserRepo.load(userId))))
    }
    
    table.insertAll(toInsert.map(connId => UserConnection(user1 = userId, user2 = connId)).toSeq: _*)
  }
}

object UserConnectionStates extends States[UserConnection]

