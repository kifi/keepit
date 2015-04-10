package com.keepit.social

import com.keepit.common.oauth.TwitterUserInfo
import com.keepit.model.TwitterId
import play.api.libs.json.Json

trait TwitterGraphTestHelper {
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

  val tweetfortytwoFollowerIds: Seq[TwitterId] = Seq(2906435114L, 2674660081L, 2905816395L, 1963841390L, 1487633766L).map(TwitterId(_))
  val tweetfortytwoFriendIds: Seq[TwitterId] = Seq(2906435114L, 2674660081L, 2905816395L, 834020252L, 14119808L, 5746452L, 20536157L).map(TwitterId(_))
}
