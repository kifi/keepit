package com.keepit.model

import com.keepit.common.concurrent.ExecutionContext

import scala.concurrent.duration.Duration

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.cache.{ Key, PrimitiveCacheImpl, FortyTwoCachePlugin, CacheStatistics }
import com.keepit.common.logging.AccessLog
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ Id, State }
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.eliza.model.MessageHandle
import com.keepit.eliza.ElizaServiceClient

@ImplementedBy(classOf[FriendRequestRepoImpl])
trait FriendRequestRepo extends Repo[FriendRequest] {
  def getBySender(userId: Id[User], states: Set[State[FriendRequest]] = Set(FriendRequestStates.ACTIVE))(implicit s: RSession): Seq[FriendRequest]
  def getByRecipient(userId: Id[User], states: Set[State[FriendRequest]] = Set(FriendRequestStates.ACTIVE))(implicit s: RSession): Seq[FriendRequest]
  def getCountBySender(userId: Id[User])(implicit s: RSession): Int
  def getCountByRecipient(userId: Id[User])(implicit s: RSession): Int
  def getBySenderAndRecipient(senderId: Id[User], recipientId: Id[User], states: Set[State[FriendRequest]] = Set(FriendRequestStates.ACTIVE))(implicit s: RSession): Option[FriendRequest]
  def getBySenderAndRecipients(senderId: Id[User], recipientIds: Set[Id[User]], states: Set[State[FriendRequest]] = Set(FriendRequestStates.ACTIVE))(implicit s: RSession): Seq[FriendRequest]
  def getBySendersAndRecipient(senderIds: Set[Id[User]], recipientId: Id[User], states: Set[State[FriendRequest]] = Set(FriendRequestStates.ACTIVE))(implicit s: RSession): Seq[FriendRequest]
}

case class FriendRequestCountKey(userId: Id[User]) extends Key[Int] {
  val namespace = "friend_request_count"
  def toKey(): String = userId.toString
}

class FriendRequestCountCache(stats: CacheStatistics, accessLog: AccessLog, inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[FriendRequestCountKey, Int](stats, accessLog, inner, outer: _*)

@Singleton
class FriendRequestRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    friendRequestCountCache: FriendRequestCountCache,
    elizaClient: ElizaServiceClient) extends DbRepo[FriendRequest] with FriendRequestRepo with Logging {

  import db.Driver.simple._

  //  implicit val messageHandleIdMapper = idMapper[MessageHandle]

  type RepoImpl = FriendRequestTable
  class FriendRequestTable(tag: Tag) extends RepoTable[FriendRequest](db, tag, "friend_request") {
    def senderId = column[Id[User]]("sender_id", O.NotNull)
    def recipientId = column[Id[User]]("recipient_id", O.NotNull)
    def messagecHandle = column[Option[Id[MessageHandle]]]("message_handle", O.Nullable)
    def * = (id.?, senderId, recipientId, createdAt, updatedAt, state, messagecHandle) <> ((FriendRequest.apply _).tupled, FriendRequest.unapply _)
  }

  def table(tag: Tag) = new FriendRequestTable(tag)
  initTable()

  override def invalidateCache(request: FriendRequest)(implicit session: RSession): Unit = {
    friendRequestCountCache.remove(FriendRequestCountKey(request.recipientId))
  }

  override def deleteCache(model: FriendRequest)(implicit session: RSession): Unit = {
    friendRequestCountCache.remove(FriendRequestCountKey(model.recipientId))
  }

  override def save(model: FriendRequest)(implicit s: RWSession): FriendRequest = {
    s.onTransactionSuccess {
      if (model.state == FriendRequestStates.IGNORED || model.state == FriendRequestStates.ACCEPTED) {
        model.messageHandle.foreach { id => elizaClient.unsendNotification(id) }
      }
    }(ExecutionContext.immediate)
    super.save(model)
  }

  def getBySender(userId: Id[User], states: Set[State[FriendRequest]] = Set(FriendRequestStates.ACTIVE))(implicit s: RSession): Seq[FriendRequest] = {
    (for (fr <- rows if fr.senderId === userId && fr.state.inSet(states)) yield fr).list
  }

  def getByRecipient(userId: Id[User], states: Set[State[FriendRequest]] = Set(FriendRequestStates.ACTIVE))(implicit s: RSession): Seq[FriendRequest] = {
    (for (fr <- rows if fr.recipientId === userId && fr.state.inSet(states)) yield fr).list
  }

  def getCountBySender(userId: Id[User])(implicit s: RSession): Int = {
    Query((for (fr <- rows if fr.senderId === userId && fr.state === FriendRequestStates.ACTIVE) yield fr).length).first
  }

  def getCountByRecipient(userId: Id[User])(implicit s: RSession): Int = {
    friendRequestCountCache.getOrElse(FriendRequestCountKey(userId)) {
      Query((for (fr <- rows if fr.recipientId === userId && fr.state === FriendRequestStates.ACTIVE) yield fr).length).first
    }
  }

  def getBySenderAndRecipient(senderId: Id[User], recipientId: Id[User], states: Set[State[FriendRequest]] = Set(FriendRequestStates.ACTIVE))(implicit s: RSession): Option[FriendRequest] = {
    (for (fr <- rows if fr.senderId === senderId && fr.recipientId === recipientId && fr.state.inSet(states)) yield fr).sortBy(_.createdAt desc).firstOption
  }

  def getBySenderAndRecipients(senderId: Id[User], recipientIds: Set[Id[User]], states: Set[State[FriendRequest]] = Set(FriendRequestStates.ACTIVE))(implicit s: RSession): Seq[FriendRequest] = {
    (for (fr <- rows if fr.senderId === senderId && fr.recipientId.inSet(recipientIds) && fr.state.inSet(states)) yield fr).list
  }

  def getBySendersAndRecipient(senderIds: Set[Id[User]], recipientId: Id[User], states: Set[State[FriendRequest]] = Set(FriendRequestStates.ACTIVE))(implicit s: RSession): Seq[FriendRequest] = {
    (for (fr <- rows if fr.senderId.inSet(senderIds) && fr.recipientId === recipientId && fr.state.inSet(states)) yield fr).list
  }
}
