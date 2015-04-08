package com.keepit.commanders.emails

import com.google.inject.{ Provider, ImplementedBy, Inject }
import com.keepit.abook.ABookServiceClient
import com.keepit.commanders.{ UserConnectionsCommander, UserCommander }
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.{ EmailToSend, TipTemplate }
import com.keepit.model.User
import com.keepit.common.mail.template.helpers.toHttpsUrl
import play.twirl.api.Html

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Random

object FriendRecommendationsEmailTip {
  val FRIEND_RECOS_TO_QUERY = 20
  val MAX_RECOS_TO_SHOW = 3
  val MIN_RECOS_TO_SHOW = 3
}

case class FriendReco(userId: Id[User], avatarUrl: String, mutualFriendsCount: Int) {
  val mutualFriendsLine = mutualFriendsCount match {
    case 0 => "Kifi user"
    case 1 => "1 mutual friend"
    case x if x > 1 => s"$x mutual friends"
  }
}

class FriendRecommendationsEmailTip @Inject() (
    abook: ABookServiceClient,
    userCommander: UserCommander,
    userConnectionsCommander: UserConnectionsCommander,
    implicit val executionContext: ExecutionContext,
    private val airbrake: AirbrakeNotifier) extends Logging {

  import FriendRecommendationsEmailTip._

  def render(emailToSend: EmailToSend, userId: Id[User]): Future[Option[Html]] = {
    getFriendRecommendationsForUser(userId) map { friendRecos =>
      if (friendRecos.size >= MIN_RECOS_TO_SHOW) Some(views.html.email.tips.friendRecommendations(friendRecos))
      else None
    }
  }

  private def getFriendRecommendationsForUser(userId: Id[User]): Future[Seq[FriendReco]] = {
    val friendIdsOptF = abook.getFriendRecommendations(userId, offset = 0, limit = FRIEND_RECOS_TO_QUERY, bePatient = true)
    friendIdsOptF flatMap {
      case Some(userIds) if userIds.size >= MIN_RECOS_TO_SHOW =>
        getManyUserImageUrls(userIds: _*) map { imageUrls =>
          userIds.sortBy { friendUserId =>
            /* kifi ghost images should be at the bottom of the list */
            (if (imageUrls(friendUserId).endsWith("/0.jpg")) 1 else -1) * Random.nextInt(Int.MaxValue)
          } take MAX_RECOS_TO_SHOW map { friendUserId =>
            val mutualFriends = userConnectionsCommander.getMutualFriends(userId, friendUserId)
            FriendReco(friendUserId, toHttpsUrl(imageUrls(friendUserId)), mutualFriends.size)
          }
        }
      case Some(userIds) =>
        log.info(s"[getFriendRecommendationsForUser $userId] not enough ($MIN_RECOS_TO_SHOW required): $userIds")
        Future.successful(Seq.empty)
      case None =>
        log.info(s"[getFriendRecommendationsForUser $userId] returned None")
        Future.successful(Seq.empty)
    } recover {
      case e =>
        airbrake.notify(s"[getFriendRecommendationsForUser $userId] failed: abook.getFriendRecommendations", e)
        Seq.empty
    }
  }

  private def getManyUserImageUrls(userIds: Id[User]*): Future[Map[Id[User], String]] = {
    val seqF = userIds.map { userId => userId -> userCommander.getUserImageUrl(userId, 100) }
    Future.traverse(seqF) { case (userId, urlF) => urlF.map(userId -> _) } map (_.toMap) recover {
      case e =>
        airbrake.notify(s"[getManyUserImageUrls $userIds] failed", e)
        Map.empty
    }
  }

}
