package com.keepit.curator.commanders.email

import com.google.inject.Inject
import com.keepit.abook.ABookServiceClient
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.{ Email, User }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.BasicUser
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.templates.Html

import scala.concurrent.Future
import scala.util.Random

object PeopleRecommendationsTip {
  val FRIEND_RECOMMENDATIONS_TO_QUERY = 20
  val FRIEND_RECOMMENDATIONS_TO_DELIVER = 5
}

sealed case class FriendReco(userId: Id[User], basicUser: BasicUser, avatarUrl: String)

class PeopleRecommendationsTip @Inject() (
    shoebox: ShoeboxServiceClient,
    abook: ABookServiceClient,
    airbrake: AirbrakeNotifier) {
  import com.keepit.curator.commanders.email.PeopleRecommendationsTip._

  def getHtml(userId: Id[User]): Future[Html] = {
    getFriendRecommendationsForUser(userId).map { friendRecos =>
      views.html.email.partials.friendRecommendations(friendRecos)
    }
  }

  private def getFriendRecommendationsForUser(userId: Id[User]): Future[Seq[FriendReco]] = {
    for {
      userIds <- abook.getFriendRecommendations(userId, offset = 0, limit = FRIEND_RECOMMENDATIONS_TO_QUERY, bePatient = true)
      if userIds.isDefined
      friends <- shoebox.getBasicUsers(userIds.get)
      friendImages <- getManyUserImageUrls(userIds.get: _*)
    } yield {
      val friendRecos = friends.map(pair => FriendReco(pair._1, pair._2, Email.helpers.toHttpsUrl(friendImages(pair._1)))).toSeq
      friendRecos.sortBy { friendReco =>
        /* kifi ghost images should be at the bottom of the list */
        (if (friendReco.avatarUrl.endsWith("/0.jpg")) 1 else -1) * Random.nextInt(Int.MaxValue)
      }.take(FRIEND_RECOMMENDATIONS_TO_DELIVER)
    }
  } recover {
    case throwable =>
      airbrake.notify(s"getFriendRecommendationsForUser($userId) failed", throwable)
      Seq.empty
  }

  // todo(josh) extract the into common (it may be required by many email templates)
  private def getManyUserImageUrls(userIds: Id[User]*): Future[Map[Id[User], String]] = {
    val seqF = userIds.map { userId => userId -> shoebox.getUserImageUrl(userId, 100) }
    Future.traverse(seqF) { pair =>
      val (userId, urlF) = pair
      urlF.map((userId, _))
    }.map(_.toMap)
  }

}
