package com.keepit.model

import com.keepit.commanders.{ UserConnectionRelationshipKey, UserConnectionRelationshipCache }

import scala.concurrent.duration.Duration
import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, Key, CacheStatistics }
import com.keepit.common.logging.AccessLog
import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.common.json.TraversableFormat
import scala.slick.jdbc.StaticQuery
import scala.slick.jdbc.StaticQuery.interpolation

@ImplementedBy(classOf[UserConnectionRepoImpl])
trait UserConnectionRepo extends Repo[UserConnection] with SeqNumberFunction[UserConnection] {
  def areConnected(u1: Id[User], u2: Id[User])(implicit session: RSession): Boolean
  def getConnectionOpt(u1: Id[User], u2: Id[User])(implicit session: RSession): Option[UserConnection]
  def getConnectedUsers(id: Id[User])(implicit session: RSession): Set[Id[User]]
  def getConnectedUsersForUsers(ids: Set[Id[User]])(implicit session: RSession): Map[Id[User], Set[Id[User]]]
  def getUnfriendedUsers(id: Id[User])(implicit session: RSession): Set[Id[User]]
  def addConnections(userId: Id[User], users: Set[Id[User]], requested: Boolean = false)(implicit session: RWSession)
  def unfriendConnections(userId: Id[User], users: Set[Id[User]])(implicit session: RWSession): Int
  def getConnectionCount(userId: Id[User])(implicit session: RSession): Int
  def getMutualConnectionCount(user1: Id[User], user2: Id[User])(implicit session: RSession): Int
  def getConnectionCounts(userIds: Set[Id[User]])(implicit session: RSession): Map[Id[User], Int]
  def deactivateAllConnections(userId: Id[User])(implicit session: RWSession): Unit
  def getUserConnectionChanged(seq: SequenceNumber[UserConnection], fetchSize: Int)(implicit session: RSession): Seq[UserConnection]
  def getBasicUserConnection(id: Id[User])(implicit session: RSession): Seq[BasicUserConnection]
}

case class UnfriendedConnectionsKey(userId: Id[User]) extends Key[Set[Id[User]]] {
  val namespace = "unfriended_connections"
  def toKey(): String = userId.id.toString
}

class UnfriendedConnectionsCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UnfriendedConnectionsKey, Set[Id[User]]](stats, accessLog, inner, outer: _*)(TraversableFormat.set(Id.format[User]))

@Singleton
class UserConnectionRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  userConnectionRelationshipCache: UserConnectionRelationshipCache,
  val friendRequestRepo: FriendRequestRepo,
  val connCountCache: UserConnectionCountCache,
  mutualConnCountCache: UserMutualConnectionCountCache,
  val userConnCache: UserConnectionIdCache,
  val basicUserConnCache: BasicUserConnectionIdCache,
  val unfriendedCache: UnfriendedConnectionsCache,
  val searchFriendsCache: SearchFriendsCache,
  override protected val changeListener: Option[RepoModification.Listener[UserConnection]])
    extends DbRepo[UserConnection] with UserConnectionRepo with SeqNumberDbFunction[UserConnection] {

  import db.Driver.simple._

  override def save(model: UserConnection)(implicit session: RWSession): UserConnection = {
    // setting a negative sequence number for deferred assignment
    val seqNum = deferredSeqNum()
    super.save(model.copy(seq = seqNum))
  }

  def invalidateCache(userId: Id[User])(implicit session: RSession): Unit = {
    userConnCache.remove(UserConnectionIdKey(userId))
    basicUserConnCache.remove(BasicUserConnectionIdKey(userId))
    connCountCache.remove(UserConnectionCountKey(userId))
    searchFriendsCache.remove(SearchFriendsKey(userId))
    unfriendedCache.remove(UnfriendedConnectionsKey(userId))
  }

  override def deleteCache(conn: UserConnection)(implicit session: RSession): Unit = {
    mutualConnCountCache.remove(UserMutualConnectionCountKey(conn.user1, conn.user2))
    userConnectionRelationshipCache.remove(UserConnectionRelationshipKey(conn.user1, conn.user2))
    userConnectionRelationshipCache.remove(UserConnectionRelationshipKey(conn.user2, conn.user1))
    List(conn.user1, conn.user2) foreach invalidateCache
  }

  override def invalidateCache(conn: UserConnection)(implicit session: RSession): Unit = deleteCache(conn)

  def getConnectionCount(userId: Id[User])(implicit session: RSession): Int = {
    connCountCache.getOrElse(UserConnectionCountKey(userId)) {
      Query((for {
        c <- rows if (c.user1 === userId || c.user2 === userId) && c.state === UserConnectionStates.ACTIVE
      } yield c).length).first
    }
  }

  val getMutualConnectionCountQ = StaticQuery.query[(Long, Long, Long, Long), Int]("""select count(*) from
      (select conn from
        (select user_2 conn from user_connection where user_1 in (?,?) and state = 'active' union all
         select user_1 conn from user_connection where user_2 in (?,?) and state = 'active') conn_ids
      group by conn having count(*) > 1) mutual_connections""")

  def getMutualConnectionCount(user1: Id[User], user2: Id[User])(implicit session: RSession): Int = {
    mutualConnCountCache.getOrElse(UserMutualConnectionCountKey(user1, user2)) {
      getMutualConnectionCountQ.first(user1.id, user2.id, user1.id, user2.id)
    }
  }

  def getConnectionCounts(userIds: Set[Id[User]])(implicit session: RSession): Map[Id[User], Int] = {
    import StaticQuery.interpolation
    import scala.collection.JavaConversions._

    val ret = connCountCache.bulkGetOrElse(userIds map UserConnectionCountKey) { keys =>
      val missingIds = keys.map(_.userId)
      val missingIdsString = missingIds.mkString(",")
      val query = sql"""select usr, count(*) from (
        select user_1 usr from user_connection where user_1 in (#${missingIdsString}) and state = 'active'
        union all
        select user_2 usr from user_connection where user_2 in (#${missingIdsString}) and state = 'active'
        ) c group by usr"""
      val results = query.as[(Id[User], Int)].list
      results.map { case (userId, cnt) => UserConnectionCountKey(userId) -> cnt }.toMap
    }
    val connectionMap = ret.map { case (key, count) => key.userId -> count }.toMap
    //for users who have no active connections
    connectionMap.withDefaultValue(0)
  }

  type RepoImpl = UserConnectionTable
  class UserConnectionTable(tag: Tag) extends RepoTable[UserConnection](db, tag, "user_connection") with SeqNumberColumn[UserConnection] {
    def user1 = column[Id[User]]("user_1", O.NotNull)
    def user2 = column[Id[User]]("user_2", O.NotNull)
    def * = (id.?, user1, user2, state, createdAt, updatedAt, seq) <> ((UserConnection.apply _).tupled, UserConnection.unapply _)
  }

  def table(tag: Tag) = new UserConnectionTable(tag)
  initTable()

  def areConnected(u1: Id[User], u2: Id[User])(implicit session: RSession): Boolean =
    (for (c <- rows if c.user1 === u1 && c.user2 === u2 || c.user2 === u1 && c.user1 === u2) yield c.state === UserConnectionStates.ACTIVE).firstOption.getOrElse(false)

  def getConnectionOpt(u1: Id[User], u2: Id[User])(implicit session: RSession): Option[UserConnection] =
    (for (c <- rows if c.user1 === u1 && c.user2 === u2 || c.user2 === u1 && c.user1 === u2) yield c).firstOption

  def getConnectedUsers(id: Id[User])(implicit session: RSession): Set[Id[User]] = {
    userConnCache.get(UserConnectionIdKey(id)) match {
      case Some(conns) => conns.map { Id[User](_) }.toSet
      case _ =>
        val conns = ((for (c <- rows if c.user1 === id && c.state === UserConnectionStates.ACTIVE) yield c.user2) union
          (for (c <- rows if c.user2 === id && c.state === UserConnectionStates.ACTIVE) yield c.user1)).list.toSet
        userConnCache.set(UserConnectionIdKey(id), conns.map(_.id).toArray)
        conns
    }
  }

  def getBasicUserConnection(id: Id[User])(implicit session: RSession): Seq[BasicUserConnection] = {
    basicUserConnCache.getOrElse(BasicUserConnectionIdKey(id)) {
      val conns = ((for (c <- rows if c.user1 === id && c.state === UserConnectionStates.ACTIVE) yield (c.user2, c.createdAt)) union
        (for (c <- rows if c.user2 === id && c.state === UserConnectionStates.ACTIVE) yield (c.user1, c.createdAt))).list.toSet
      val basicConnections: Set[BasicUserConnection] = (conns.map { c => BasicUserConnection(c._1, c._2) }).toSet
      basicConnections.toSeq.sortWith { (c1, c2) => c1.createdAt.isAfter(c2.createdAt) }
    }
  }

  def getConnectedUsersForUsers(ids: Set[Id[User]])(implicit session: RSession): Map[Id[User], Set[Id[User]]] = {
    val ret = userConnCache.bulkGetOrElse(ids map UserConnectionIdKey) { keys =>
      val missing = keys.map(_.userId)

      val query =
        ((for (c <- rows if c.user1.inSet(missing) && c.state === UserConnectionStates.ACTIVE) yield (c.user1, c.user2)) union
          (for (c <- rows if c.user2.inSet(missing) && c.state === UserConnectionStates.ACTIVE) yield (c.user2, c.user1)))

      query.list().groupBy(_._1).map {
        case (id, connections) => UserConnectionIdKey(id) -> connections.map(_._2.id).toArray
      }.toMap
    }
    ret.map { case (key, value) => key.userId -> value.map(Id[User]).toSet }.toMap
  }

  def getUnfriendedUsers(id: Id[User])(implicit session: RSession): Set[Id[User]] = {
    unfriendedCache.getOrElse(UnfriendedConnectionsKey(id)) {
      ((for (c <- rows if c.user1 === id && c.state === UserConnectionStates.UNFRIENDED) yield c.user2) union
        (for (c <- rows if c.user2 === id && c.state === UserConnectionStates.UNFRIENDED) yield c.user1)).list.toSet
    }
  }

  def deactivateAllConnections(userId: Id[User])(implicit session: RWSession): Unit = {
    val allConnections = (for { conn <- rows if conn.user1 === userId || conn.user2 === userId } yield conn).list
    val changedUsers: Set[Id[User]] = for {
      conn: UserConnection <- allConnections.toSet
      changedUser <- {
        save(conn.copy(state = UserConnectionStates.INACTIVE))
        Seq(conn.user1, conn.user2)
      }
    } yield changedUser

    changedUsers foreach invalidateCache
  }

  def unfriendConnections(userId: Id[User], users: Set[Id[User]])(implicit session: RWSession): Int = {
    if (users.nonEmpty) {
      val ids = (for {
        c <- rows if c.user2 === userId && c.user1.inSet(users) || c.user1 === userId && c.user2.inSet(users)
      } yield c.id).list

      ids.foreach { id =>
        (for { c <- rows if c.id === id } yield (c.state, c.seq)).update(UserConnectionStates.UNFRIENDED, deferredSeqNum())
      }

      (friendRequestRepo.getBySender(userId).filter(users contains _.recipientId) ++
        friendRequestRepo.getByRecipient(userId).filter(users contains _.senderId)) map { friendRequest =>
          friendRequestRepo.save(friendRequest.copy(state = FriendRequestStates.IGNORED))
        }

      (users + userId) foreach invalidateCache
      ids.size
    } else {
      0
    }
  }

  def addConnections(userId: Id[User], users: Set[Id[User]], requested: Boolean = false)(implicit session: RWSession): Unit = {
    if (users.nonEmpty) {
      val ids = (for {
        c <- rows if (c.user2 === userId && c.user1.inSet(users) || c.user1 === userId && c.user2.inSet(users)) &&
          (if (requested) c.state =!= UserConnectionStates.ACTIVE else c.state === UserConnectionStates.INACTIVE)
      } yield c.id).list

      ids.foreach { id =>
        (for { c <- rows if c.id === id } yield (c.state, c.seq)).update(UserConnectionStates.ACTIVE, deferredSeqNum())
      }

      val toInsert = users -- {
        (for (c <- rows if c.user1 === userId) yield c.user2) union
          (for (c <- rows if c.user2 === userId) yield c.user1)
      }.list.toSet

      (friendRequestRepo.getBySender(userId).filter(users contains _.recipientId) ++
        friendRequestRepo.getByRecipient(userId).filter(users contains _.senderId)) map { friendRequest =>
          friendRequestRepo.save(friendRequest.copy(state = FriendRequestStates.ACCEPTED))
        }

      (users + userId) foreach invalidateCache

      rows.insertAll(toInsert.map { connId => UserConnection(createdAt = clock.now, user1 = userId, user2 = connId, seq = deferredSeqNum()) }.toSeq: _*)
    }
  }

  def getUserConnectionChanged(seq: SequenceNumber[UserConnection], fetchSize: Int)(implicit session: RSession): Seq[UserConnection] = super.getBySequenceNumber(seq, fetchSize)
}
