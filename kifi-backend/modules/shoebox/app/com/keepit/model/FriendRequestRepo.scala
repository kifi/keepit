package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
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
  def getBySenderAndRecipient(senderId: Id[User], recipientId: Id[User], includeAccepted: Boolean = false)
      (implicit s: RSession): Option[FriendRequest]
}

@Singleton
class FriendRequestRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock
  ) extends DbRepo[FriendRequest] with FriendRequestRepo with Logging {

  import db.Driver.Implicit._
  import DBSession._
  import FortyTwoTypeMappers._

  override val table = new RepoTable[FriendRequest](db, "friend_request") {
    def senderId = column[Id[User]]("sender_id", O.NotNull)
    def recipientId = column[Id[User]]("recipient_id", O.NotNull)
    def * = id.? ~ senderId ~ recipientId ~ createdAt ~ updatedAt ~ state <> (FriendRequest.apply _, FriendRequest.unapply _)
  }

  def getBySender(userId: Id[User], states: Set[State[FriendRequest]] = Set(FriendRequestStates.ACTIVE))
      (implicit s:RSession): Seq[FriendRequest] = {
    (for (fr <- table if fr.senderId === userId && fr.state.inSet(states)) yield fr).list
  }

  def getByRecipient(userId: Id[User], states: Set[State[FriendRequest]] = Set(FriendRequestStates.ACTIVE))
      (implicit s: RSession): Seq[FriendRequest] = {
    (for (fr <- table if fr.recipientId === userId && fr.state.inSet(states)) yield fr).list
  }

  def getBySenderAndRecipient(senderId: Id[User], recipientId: Id[User], includeAccepted: Boolean = false)
      (implicit s: RSession): Option[FriendRequest] = {
    import FriendRequestStates._
    val states = if (includeAccepted) Set(ACTIVE, ACCEPTED) else Set(ACTIVE)
    (for (fr <- table if fr.senderId === senderId && fr.recipientId === recipientId &&
      fr.state.inSet(states)) yield fr).sortBy(_.createdAt desc).firstOption
  }
}

object FriendRequestStates extends States[FriendRequest] {
  val ACCEPTED = State[FriendRequest]("accepted")
  val IGNORED = State[FriendRequest]("ignored")
}
