package com.keepit.social

import java.io.File

import com.google.inject.Inject
import com.keepit.common.concurrent.WatchableExecutionContext
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.EmailAddress
import com.keepit.common.net.FakeWSResponse
import com.keepit.common.oauth.{ TwitterUserInfo, UserProfileInfo, TwitterOAuthProviderImpl, OAuth1Configuration }
import com.keepit.common.social._
import com.keepit.common.time.Clock
import com.keepit.model._
import play.api.libs.json.{ JsNull, JsArray, JsValue, JsObject }
import play.api.libs.ws.WSResponse
import securesocial.core.{ IdentityId, OAuth2Settings }

import scala.concurrent.Future
import scala.util.Try

class FakeTwitterSocialGraph @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    clock: Clock,
    executionContext: WatchableExecutionContext,
    oauth1Config: OAuth1Configuration,
    userValueRepo: UserValueRepo,
    twitterSyncStateRepo: TwitterSyncStateRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    implicit val publicIdConfig: PublicIdConfiguration,
    socialRepo: SocialUserInfoRepo) extends TwitterSocialGraph with TwitterGraphTestHelper {

  val twtrOAuthProvider = new TwitterOAuthProviderImpl(airbrake, oauth1Config) {
    override def getUserProfileInfo(accessToken: OAuth1TokenInfo): Future[UserProfileInfo] = Future.successful {
      TwitterUserInfo.toUserProfileInfo(tweetfortytwoInfo.copy(screenName = "tweet42"))
    }
  }

  val twtrGraph: TwitterSocialGraphImpl = new TwitterSocialGraphImpl(airbrake, db, clock, oauth1Config, twtrOAuthProvider, userValueRepo, twitterSyncStateRepo, libraryMembershipRepo, socialRepo, publicIdConfig, executionContext) {
    override protected def lookupUsers(socialUserInfo: SocialUserInfo, accessToken: OAuth1TokenInfo, mutualFollows: Set[TwitterId]): Future[JsValue] = Future.successful {
      socialUserInfo.socialId.id.toLong match {
        case tweetfortytwoInfo.id =>
          JsArray(infos.values.collect { case (json, info) if info.id != tweetfortytwoInfo.id => json }.toSeq)
        case _ =>
          JsNull
      }
    }

    protected def fetchIds(socialUserInfo: SocialUserInfo, accessToken: OAuth1TokenInfo, userId: Long, endpoint: String): Future[Seq[TwitterId]] = Future.successful {
      socialUserInfo.socialId.id.toLong match {
        case tweetfortytwoInfo.id =>
          if (endpoint.contains("followers")) tweetfortytwoFollowerIds
          else if (endpoint.contains("friends")) tweetfortytwoFriendIds
          else Seq.empty
        case _ =>
          if (endpoint.contains("followers")) {
            Seq(1L, 2L, 3L, 4L).map(TwitterId(_))
          } else if (endpoint.contains("friends")) {
            Seq(2L, 3L).map(TwitterId(_))
          } else Seq.empty
      }
    }

    override def sendDM(socialUserInfo: SocialUserInfo, receiverUserId: Long, msg: String): Future[WSResponse] = Future.successful {
      val body =
        s"""
          {
            "recipient_id": $receiverUserId
          }
        """.stripMargin
      new FakeWSResponse()
    }

    override def sendTweet(socialUserInfo: SocialUserInfo, image: Option[File], msg: String): Unit = {}

  }

  def fetchTweets(socialUserInfoOpt: Option[SocialUserInfo], handle: String, sinceId: Long): Future[Seq[JsObject]] = Future.successful {
    Seq.empty
  }

  def extractEmails(parentJson: JsValue): Seq[EmailAddress] = twtrGraph.extractEmails(parentJson)
  def extractFriends(parentJson: JsValue): Seq[SocialUserInfo] = twtrGraph.extractFriends(parentJson)
  def updateSocialUserInfo(sui: SocialUserInfo, json: JsValue): SocialUserInfo = twtrGraph.updateSocialUserInfo(sui, json)
  def vetJsAccessToken(settings: OAuth2Settings, json: JsValue): Try[IdentityId] = twtrGraph.vetJsAccessToken(settings, json)
  def revokePermissions(socialUserInfo: SocialUserInfo): Future[Unit] = twtrGraph.revokePermissions(socialUserInfo)
  def extractUserValues(json: JsValue): Map[UserValueName, String] = twtrGraph.extractUserValues(json)
  def fetchSocialUserRawInfo(socialUserInfo: SocialUserInfo): Option[SocialUserRawInfo] = twtrGraph.fetchSocialUserRawInfo(socialUserInfo)
  def sendTweet(socialUserInfo: SocialUserInfo, image: Option[File], msg: String): Unit = twtrGraph.sendTweet(socialUserInfo, image, msg)
  def sendDM(socialUserInfo: SocialUserInfo, receiverUserId: Long, msg: String): Future[WSResponse] = twtrGraph.sendDM(socialUserInfo, receiverUserId, msg)
}
