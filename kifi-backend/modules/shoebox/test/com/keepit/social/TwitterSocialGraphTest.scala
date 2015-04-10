package com.keepit.social

import com.google.inject.Injector
import com.keepit.commanders.{ KifiInstallationCommander, LibraryImageCommander }
import com.keepit.common.concurrent.{ WatchableExecutionContext, FakeExecutionContextModule }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.oauth._
import com.keepit.common.social.{ BasicUserRepo, TwitterSocialGraphImpl }
import com.keepit.common.store.S3ImageStore
import com.keepit.common.time.Clock
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsArray, Json, JsNull, JsValue }
import securesocial.core.{ IdentityId, AuthenticationMethod, SocialUser, OAuth1Info }

import scala.concurrent.{ ExecutionContext, Future }

class TwitterSocialGraphTest extends Specification with ShoeboxTestInjector with TwitterGraphTestHelper {

  def setup()(implicit injector: Injector) = {
    val db = inject[Database]
    val clock = inject[Clock]
    val airbrake = inject[AirbrakeNotifier]
    val oauth1Config = inject[OAuth1Configuration]
    val userValueRepo = inject[UserValueRepo]
    val twitterSyncStateRepo = inject[TwitterSyncStateRepo]
    val executionContext = inject[WatchableExecutionContext]
    val libraryMembershipRepo = inject[LibraryMembershipRepo]
    val twtrOAuthProvider = new TwitterOAuthProviderImpl(airbrake, oauth1Config) {
      override def getUserProfileInfo(accessToken: OAuth1TokenInfo): Future[UserProfileInfo] = Future.successful {
        TwitterUserInfo.toUserProfileInfo(tweetfortytwoInfo.copy(screenName = "tweet42"))
      }
    }
    val twtrGraph: TwitterSocialGraphImpl = new TwitterSocialGraphImpl(airbrake, db, inject[S3ImageStore], clock, oauth1Config, twtrOAuthProvider, userValueRepo, twitterSyncStateRepo, libraryMembershipRepo, libraryRepo, basicUserRepo, socialUserInfoRepo, inject[LibraryImageCommander], inject[ElizaServiceClient], inject[KifiInstallationCommander], inject[PublicIdConfiguration], inject[WatchableExecutionContext]) {
      override protected def lookupUsers(socialUserInfo: SocialUserInfo, accessToken: OAuth1TokenInfo, mutualFollows: Set[TwitterId]): Future[JsValue] = Future.successful {
        socialUserInfo.socialId.id.toLong match {
          case tweetfortytwoInfo.id =>
            JsArray(infos.values.collect { case (json, info) if info.id != tweetfortytwoInfo.id => json }.toSeq)
          case _ =>
            JsNull
        }
      }

      override def fetchIds(socialUserInfo: SocialUserInfo, accessToken: OAuth1TokenInfo, userId: TwitterId, endpoint: String): Future[Seq[TwitterId]] = Future.successful {
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
    }

    val oauth1Info = OAuth1Info("twitter-oauth1-token", "foobar")
    val identityId = IdentityId(tweetfortytwoInfo.id.toString, ProviderIds.Twitter.id)
    val socialUser = SocialUser(identityId, "TweetFortyTwo", "Eng", "TweetFortyTwo Eng", None, None, AuthenticationMethod.OAuth1, oAuth1Info = Some(oauth1Info))

    val user = UserFactory.user().get
    val sui = db.readWrite { implicit rw =>
      socialUserInfoRepo.save(SocialUserInfo(
        userId = user.id,
        fullName = socialUser.fullName,
        pictureUrl = Some("http://random/rand.png"),
        profileUrl = None, // not set
        socialId = SocialId(identityId.userId),
        networkType = SocialNetworks.TWITTER,
        credentials = Some(socialUser)
      ))
    }
    executionContext.drain(1000)
    (twtrGraph, sui)
  }

  "TwitterUserInfo" should {
    "parse Twitter user object" in {
      tweetfortytwoInfo.id === (tweetfortytwoJson \ "id").as[Long]
      tweetfortytwoInfo.screenName === (tweetfortytwoJson \ "screen_name").as[String]
      tweetfortytwoInfo.defaultProfileImage === (tweetfortytwoJson \ "default_profile_image").as[Boolean]
    }
    "discard default egg profile image" in {
      tweetfortytwoInfo.defaultProfileImage === true
      tweetfortytwoInfo.profileImageUrl.toString === (tweetfortytwoJson \ "profile_image_url_https").as[String]
      tweetfortytwoInfo.pictureUrl === None
    }
    "strip out _normal variant in image name" in {
      kifirayInfo.profileImageUrl.toString === (kifirayJson \ "profile_image_url_https").as[String]
      kifirayInfo.pictureUrl.isDefined === true
      kifirayInfo.pictureUrl.exists(!_.toString.contains("_normal")) === true
      kifirayInfo.pictureUrl.map(_.toString).get === "https://pbs.twimg.com/profile_images/535882931399450624/p7jzsrJH.jpeg"
    }
    "convert to UserProfileInfo" in {
      val upi: UserProfileInfo = TwitterUserInfo.toUserProfileInfo(kifirayInfo)
      upi.userId.id === kifirayInfo.id.toString
      upi.name === kifirayInfo.name
      upi.handle.map(_.underlying).get === kifirayInfo.screenName
      upi.pictureUrl === kifirayInfo.pictureUrl
      upi.profileUrl.get === kifirayInfo.profileUrl
    }
  }
  "TwitterSocialGraph" should {
    "fetch from twitter" in {
      withDb(FakeExecutionContextModule()) { implicit injector =>
        val (twtrGraph, sui) = setup()
        val Some(raw) = twtrGraph.fetchSocialUserRawInfo(sui)
        raw.socialId === SocialId(tweetfortytwoInfo.id.toString)
        val jsonSeq = raw.jsons.head.as[JsArray].value // ok for small data set
        val expectedMutualIds = tweetfortytwoFollowerIds.intersect(tweetfortytwoFriendIds)
        jsonSeq.length === expectedMutualIds.length
        val extractedIds = jsonSeq.map(json => (json \ "id").as[Long]).map(TwitterId(_)).toSet
        extractedIds === expectedMutualIds.toSet
      }
    }
    "update SocialUserInfo" in {
      withDb() { implicit injector =>
        val (twtrGraph, sui) = setup()
        val updated = twtrGraph.updateSocialUserInfo(sui, JsNull)
        updated.profileUrl.get === s"https://www.twitter.com/tweet42"
      }
    }
  }

}
