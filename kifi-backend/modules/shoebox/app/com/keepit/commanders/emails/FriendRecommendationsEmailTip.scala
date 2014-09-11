package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.abook.ABookServiceClient
import com.keepit.commanders.UserCommander
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.template.{ EmailToSend, TipTemplate }
import com.keepit.common.social.BasicUserRepo
import com.keepit.model.User
import com.keepit.common.mail.template.helpers.toHttpsUrl
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.twirl.api.Html

import scala.concurrent.Future
import scala.util.Random

object FriendRecommendationsEmailTip {
  val FRIEND_RECOMMENDATIONS_TO_QUERY = 20
  val FRIEND_RECOMMENDATIONS_TO_DELIVER = 5
}

sealed case class FriendReco(userId: Id[User], avatarUrl: String)

class FriendRecommendationsEmailTip @Inject() (
    basicUserRepo: BasicUserRepo,
    abook: ABookServiceClient,
    userCommander: UserCommander,
    airbrake: AirbrakeNotifier) extends TipTemplate {

  import FriendRecommendationsEmailTip._

  def render(emailToSend: EmailToSend): Option[Future[Html]] = {
    val userIdOpt = emailToSend.to.fold(id => Some(id), _ => None)
    userIdOpt map { userId =>
      Some(getFriendRecommendationsForUser(userId) map { friendRecos =>
        views.html.email.tips.friendRecommendations(friendRecos)
      })
    } getOrElse None
  }

  private def getFriendRecommendationsForUser(userId: Id[User]): Future[Seq[FriendReco]] = {
    for {
      userIds <- abook.getFriendRecommendations(userId, offset = 0, limit = FRIEND_RECOMMENDATIONS_TO_QUERY, bePatient = true)
      if userIds.isDefined
      imageUrls <- getManyUserImageUrls(userIds.get: _*)
    } yield {
      userIds.get.sortBy { userId =>
        /* kifi ghost images should be at the bottom of the list */
        (if (imageUrls(userId).endsWith("/0.jpg")) 1 else -1) * Random.nextInt(Int.MaxValue)
      }.take(FRIEND_RECOMMENDATIONS_TO_DELIVER).map(userId => FriendReco(userId, toHttpsUrl(imageUrls(userId))))
    }
  } recover {
    case throwable =>
      airbrake.notify(s"getFriendRecommendationsForUser($userId) failed", throwable)
      Seq.empty
  }

  private def getManyUserImageUrls(userIds: Id[User]*): Future[Map[Id[User], String]] = {
    val seqF = userIds.map { userId => userId -> userCommander.getUserImageUrl(userId, 100) }
    Future.traverse(seqF) { case (userId, urlF) => urlF.map(userId -> _) }.map(_.toMap)
  }

}
