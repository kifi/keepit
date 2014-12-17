package com.keepit.social

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.oauth.{ ProviderIds, TwitterUserInfo, TwitterOAuthProvider, OAuth1Configuration }
import com.keepit.common.social.TwitterSocialGraph
import com.keepit.model.{ OAuth1TokenInfo, SocialUserInfo }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsArray, Json, JsNull, JsValue }
import securesocial.core.{ IdentityId, AuthenticationMethod, SocialUser, OAuth1Info }

import scala.concurrent.Future

class TwitterSocialGraphTest extends Specification with ShoeboxTestInjector {

  val tweetfortytwoRaw =
    """
      {
        "id": 2901460275,
        "id_str": "2901460275",
        "name": "TweetFortyTwo Eng",
        "screen_name": "tweetfortytwo",
        "protected": false,
        "followers_count": 5,
        "friends_count": 45,
        "listed_count": 2,
        "created_at": "Mon Dec 01 23:43:36 +0000 2014",
        "favourites_count": 0,
        "utc_offset": null,
        "time_zone": null,
        "geo_enabled": false,
        "verified": false,
        "statuses_count": 1,
        "lang": "en",
        "contributors_enabled": false,
        "is_translator": false,
        "is_translation_enabled": false,
        "profile_background_color": "C0DEED",
        "profile_background_image_url": "http://abs.twimg.com/images/themes/theme1/bg.png",
        "profile_background_image_url_https": "https://abs.twimg.com/images/themes/theme1/bg.png",
        "profile_background_tile": false,
        "profile_image_url": "http://abs.twimg.com/sticky/default_profile_images/default_profile_0_normal.png",
        "profile_image_url_https": "https://abs.twimg.com/sticky/default_profile_images/default_profile_0_normal.png",
        "profile_link_color": "0084B4",
        "profile_sidebar_border_color": "C0DEED",
        "profile_sidebar_fill_color": "DDEEF6",
        "profile_text_color": "333333",
        "profile_use_background_image": true,
        "default_profile": true,
        "default_profile_image": true,
        "following": false,
        "follow_request_sent": false,
        "notifications": false
      }
    """

  val kifirayRaw =
    """
      {
        "id": 2674660081,
        "id_str": "2674660081",
        "name": "Kifi Ray",
        "screen_name": "kifiray",
        "location": "",
        "profile_location": null,
        "description": "",
        "url": null,
        "entities": {
          "description": {
            "urls": [

            ]
          }
        },
        "protected": false,
        "followers_count": 2,
        "friends_count": 24,
        "listed_count": 0,
        "created_at": "Wed Jul 23 17:57:01 +0000 2014",
        "favourites_count": 0,
        "utc_offset": null,
        "time_zone": null,
        "geo_enabled": false,
        "verified": false,
        "statuses_count": 0,
        "lang": "en",
        "contributors_enabled": false,
        "is_translator": false,
        "is_translation_enabled": false,
        "profile_background_color": "C0DEED",
        "profile_background_image_url": "http://abs.twimg.com/images/themes/theme1/bg.png",
        "profile_background_image_url_https": "https://abs.twimg.com/images/themes/theme1/bg.png",
        "profile_background_tile": false,
        "profile_image_url": "http://pbs.twimg.com/profile_images/535882931399450624/p7jzsrJH_normal.jpeg",
        "profile_image_url_https": "https://pbs.twimg.com/profile_images/535882931399450624/p7jzsrJH_normal.jpeg",
        "profile_link_color": "0084B4",
        "profile_sidebar_border_color": "C0DEED",
        "profile_sidebar_fill_color": "DDEEF6",
        "profile_text_color": "333333",
        "profile_use_background_image": true,
        "default_profile": true,
        "default_profile_image": false,
        "following": true,
        "follow_request_sent": false,
        "notifications": false
      }
      """

  val kifiwoofRaw =
    """
      {
        "id": 2906435114,
        "id_str": "2906435114",
        "name": "Kifi Woof",
        "screen_name": "kifiwoof",
        "location": "",
        "profile_location": null,
        "description": "",
        "url": null,
        "entities": {
          "description": {
            "urls": [

            ]
          }
        },
        "protected": false,
        "followers_count": 5,
        "friends_count": 43,
        "listed_count": 0,
        "created_at": "Fri Nov 21 21:53:06 +0000 2014",
        "favourites_count": 0,
        "utc_offset": null,
        "time_zone": null,
        "geo_enabled": false,
        "verified": false,
        "statuses_count": 0,
        "lang": "en",
        "contributors_enabled": false,
        "is_translator": false,
        "is_translation_enabled": false,
        "profile_background_color": "C0DEED",
        "profile_background_image_url": "http://abs.twimg.com/images/themes/theme1/bg.png",
        "profile_background_image_url_https": "https://abs.twimg.com/images/themes/theme1/bg.png",
        "profile_background_tile": false,
        "profile_image_url": "http://pbs.twimg.com/profile_images/535914041244258305/DX29q_uf_normal.jpeg",
        "profile_image_url_https": "https://pbs.twimg.com/profile_images/535914041244258305/DX29q_uf_normal.jpeg",
        "profile_link_color": "0084B4",
        "profile_sidebar_border_color": "C0DEED",
        "profile_sidebar_fill_color": "DDEEF6",
        "profile_text_color": "333333",
        "profile_use_background_image": true,
        "default_profile": true,
        "default_profile_image": false,
        "following": true,
        "follow_request_sent": false,
        "notifications": false
      }
    """

  val linked42Raw =
    """
      {
        "id": 2905816395,
        "id_str": "2905816395",
        "name": "Linked42",
        "screen_name": "linked42",
        "location": "",
        "profile_location": null,
        "description": "",
        "url": null,
        "entities": {
          "description": {
            "urls": [

            ]
          }
        },
        "protected": false,
        "followers_count": 1,
        "friends_count": 41,
        "listed_count": 0,
        "created_at": "Fri Dec 05 01:43:33 +0000 2014",
        "favourites_count": 0,
        "utc_offset": null,
        "time_zone": null,
        "geo_enabled": false,
        "verified": false,
        "statuses_count": 0,
        "lang": "en",
        "contributors_enabled": false,
        "is_translator": false,
        "is_translation_enabled": false,
        "profile_background_color": "C0DEED",
        "profile_background_image_url": "http://abs.twimg.com/images/themes/theme1/bg.png",
        "profile_background_image_url_https": "https://abs.twimg.com/images/themes/theme1/bg.png",
        "profile_background_tile": false,
        "profile_image_url": "http://abs.twimg.com/sticky/default_profile_images/default_profile_6_normal.png",
        "profile_image_url_https": "https://abs.twimg.com/sticky/default_profile_images/default_profile_6_normal.png",
        "profile_link_color": "0084B4",
        "profile_sidebar_border_color": "C0DEED",
        "profile_sidebar_fill_color": "DDEEF6",
        "profile_text_color": "333333",
        "profile_use_background_image": true,
        "default_profile": true,
        "default_profile_image": true,
        "following": true,
        "follow_request_sent": false,
        "notifications": false
      }
    """

  val tweetfortytwoJson = Json.parse(tweetfortytwoRaw)
  val kifirayJson = Json.parse(kifirayRaw)
  val kifiwoofJson = Json.parse(kifiwoofRaw)
  val linked42Json = Json.parse(linked42Raw)

  val tweetfortytwoInfo = tweetfortytwoJson.as[TwitterUserInfo]
  val kifirayInfo = kifirayJson.as[TwitterUserInfo]
  val kifiwoofInfo = kifiwoofJson.as[TwitterUserInfo]
  val linked42Info = linked42Json.as[TwitterUserInfo]

  val infos = Map(
    tweetfortytwoInfo.id -> (tweetfortytwoJson, tweetfortytwoInfo),
    kifirayInfo.id -> (kifirayJson, kifirayInfo),
    kifiwoofInfo.id -> (kifiwoofJson, kifiwoofInfo),
    linked42Info.id -> (linked42Json, linked42Info)
  )

  val tweetfortytwoFollowerIds: Seq[Long] = Seq(2906435114L, 2674660081L, 2905816395L, 1963841390L, 1487633766L)
  val tweetfortytwoFriendIds: Seq[Long] = Seq(2906435114L, 2674660081L, 2905816395L, 834020252L, 14119808L, 5746452L, 20536157L)

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
  }
  "TwitterSocialGraph" should {
    "fetch from twitter" in {
      withDb() { implicit injector =>
        val airbrake = inject[AirbrakeNotifier]
        val oauth1Config = inject[OAuth1Configuration]
        val twtrOAuthProvider = inject[TwitterOAuthProvider]
        val twtrGraph: TwitterSocialGraph = new TwitterSocialGraph(airbrake, db, oauth1Config, twtrOAuthProvider, socialUserInfoRepo) {
          override protected def lookupUsers(socialUserInfo: SocialUserInfo, accessToken: OAuth1TokenInfo, mutualFollows: Set[Long]): Future[JsValue] = Future.successful {
            socialUserInfo.socialId.id.toLong match {
              case tweetfortytwoInfo.id =>
                JsArray(infos.values.collect { case (json, info) if info.id != tweetfortytwoInfo.id => json }.toSeq)
              case _ =>
                JsNull
            }
          }

          override protected def fetchIds(socialUserInfo: SocialUserInfo, accessToken: OAuth1TokenInfo, userId: Long, endpoint: String): Future[Seq[Long]] = Future.successful {
            socialUserInfo.socialId.id.toLong match {
              case tweetfortytwoInfo.id =>
                if (endpoint.contains("followers")) tweetfortytwoFollowerIds
                else if (endpoint.contains("friends")) tweetfortytwoFriendIds
                else Seq.empty
              case _ =>
                if (endpoint.contains("followers")) { Seq(1L, 2L, 3L, 4L) }
                else if (endpoint.contains("friends")) { Seq(2L, 3L) }
                else Seq.empty
            }
          }
        }

        val oauth1Info = OAuth1Info("twitter-oauth1-token", "foobar")
        val identityId = IdentityId(tweetfortytwoInfo.id.toString, ProviderIds.Twitter.id)
        val socialUser = SocialUser(identityId, "TweetFortyTwo", "Eng", "TweetFortyTwo Eng", None, None, AuthenticationMethod.OAuth1, oAuth1Info = Some(oauth1Info))
        val Some(raw) = twtrGraph.fetchSocialUserRawInfo(TwitterUserInfo.toSocialUserInfo(tweetfortytwoInfo).copy(credentials = Some(socialUser)))
        raw.socialId === SocialId(tweetfortytwoInfo.id.toString)
        val jsonSeq = raw.jsons.head.as[JsArray].value // ok for small data set
        val expectedMutualIds = tweetfortytwoFollowerIds.intersect(tweetfortytwoFriendIds)
        jsonSeq.length === expectedMutualIds.length
        val extractedIds = jsonSeq.map(json => (json \ "id").as[Long]).toSet
        extractedIds === expectedMutualIds.toSet
      }
    }
  }

}
