package com.keepit.commanders.emails.activity

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model.{ FriendRequestRepo, FriendRequestStates, User }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

class ConnectionRequestCountComponent @Inject() (db: Database, friendRequestRepo: FriendRequestRepo) {

  def apply(recipientId: Id[User]): Future[Int] = {
    db.readOnlyReplicaAsync { implicit session => friendRequestRepo.getCountByRecipient(recipientId) }
  }

}

class ConnectionRequestsComponent @Inject() (db: Database, friendRequestRepo: FriendRequestRepo) {

  // max number of friend requests to display
  val maxFriendRequests = 10

  def apply(recipientId: Id[User]): Future[Seq[Id[User]]] = {
    db.readOnlyReplicaAsync { implicit session =>
      friendRequestRepo.getByRecipient(userId = recipientId, states = Set(FriendRequestStates.ACTIVE))
    } map { _ sortBy (-_.updatedAt.getMillis) map (_.senderId) take maxFriendRequests }
  }

}
