package com.keepit.model

import scala.concurrent.duration.Duration

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.cache.{Key, PrimitiveCacheImpl, FortyTwoCachePlugin}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.db.{States, Id, State}
import com.keepit.common.logging.Logging
import com.keepit.common.time._

@ImplementedBy(classOf[FriendRequestRepoImpl])
trait FriendRequestRepo extends Repo[FriendRequest] {
  def getBySender(userId: Id[User], states: Set[State[FriendRequest]] = Set(FriendRequestStates.ACTIVE))
      (implicit s:RSession): Seq[FriendRequest]
  def getByRecipient(userId: Id[User], states: Set[State[FriendRequest]] = Set(FriendRequestStates.ACTIVE))
      (implicit s: RSession): Seq[FriendRequest]
  def getCountByRecipient(userId: Id[User])(implicit s: RSession): Int
  def getBySenderAndRecipient(senderId: Id[User], recipientId: Id[User],
      states: Set[State[FriendRequest]] = Set(FriendRequestStates.ACTIVE))(implicit s: RSession): Option[FriendRequest]
}

case class FriendRequestCountKey(userId: Id[User]) extends Key[Int] {
  val namespace = "friend_request_count"
  def toKey(): String = userId.toString
}

class FriendRequestCountCache(inner: (FortyTwoCachePlugin, Duration), outer: (FortyTwoCachePlugin, Duration)*)
    extends PrimitiveCacheImpl[FriendRequestCountKey, Int](inner, outer: _*)

@Singleton
class FriendRequestRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    friendRequestCountCache: FriendRequestCountCache
  ) extends DbRepo[FriendRequest] with FriendRequestRepo with Logging {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._
  import scala.slick.lifted.Query

  override val table = new RepoTable[FriendRequest](db, "friend_request") {
    def senderId = column[Id[User]]("sender_id", O.NotNull)
    def recipientId = column[Id[User]]("recipient_id", O.NotNull)
    def * = id.? ~ senderId ~ recipientId ~ createdAt ~ updatedAt ~ state <> (FriendRequest.apply _, FriendRequest.unapply _)
  }

  override def invalidateCache(request: FriendRequest)(implicit session: RSession): FriendRequest = {
    friendRequestCountCache.remove(FriendRequestCountKey(request.recipientId))
    super.invalidateCache(request)
  }

  def getCountByRecipient(userId: Id[User])(implicit s: RSession): Int = {
    friendRequestCountCache.getOrElse(FriendRequestCountKey(userId)) {
      Query(
        (for (fr <- table if fr.recipientId === userId && fr.state === FriendRequestStates.ACTIVE) yield fr).length
      ).first
    }
  }

  def getBySender(userId: Id[User], states: Set[State[FriendRequest]] = Set(FriendRequestStates.ACTIVE))
      (implicit s: RSession): Seq[FriendRequest] = {
    (for (fr <- table if fr.senderId === userId && fr.state.inSet(states)) yield fr).list
  }

  def getByRecipient(userId: Id[User], states: Set[State[FriendRequest]] = Set(FriendRequestStates.ACTIVE))
      (implicit s: RSession): Seq[FriendRequest] = {
    (for (fr <- table if fr.recipientId === userId && fr.state.inSet(states)) yield fr).list
  }

  def getBySenderAndRecipient(senderId: Id[User], recipientId: Id[User],
      states: Set[State[FriendRequest]] = Set(FriendRequestStates.ACTIVE))
      (implicit s: RSession): Option[FriendRequest] = {
    (for (fr <- table if fr.senderId === senderId && fr.recipientId === recipientId &&
      fr.state.inSet(states)) yield fr).sortBy(_.createdAt desc).firstOption
  }
}

object FriendRequestStates extends States[FriendRequest] {
  val ACCEPTED = State[FriendRequest]("accepted")
  val IGNORED = State[FriendRequest]("ignored")
}
