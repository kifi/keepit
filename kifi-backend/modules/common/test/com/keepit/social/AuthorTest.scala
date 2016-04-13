package com.keepit.social

import com.keepit.common.store.StaticImageUrls
import org.specs2.mutable.Specification
import play.api.libs.json.Json

class AuthorTest extends Specification {
  "BasicAuthor" should {
    "be formatted properly" in {
      Json.toJson(BasicAuthor.KifiUser("42424242-4242-4242-424242424242", "Ryan Brewster", StaticImageUrls.KIFI_LOGO, "https://www.kifi.com/ryanpbrewster")) === Json.obj(
        "kind" -> "kifi",
        "id" -> "42424242-4242-4242-424242424242",
        "name" -> "Ryan Brewster",
        "picture" -> StaticImageUrls.KIFI_LOGO,
        "url" -> "https://www.kifi.com/ryanpbrewster"
      )

      Json.toJson(BasicAuthor.SlackUser("U12341234", "@ryanpbrewster", StaticImageUrls.SLACK_LOGO, "https://www.kifi.slack.com/archives/foo")) === Json.obj(
        "kind" -> "slack",
        "id" -> "U12341234",
        "name" -> "@ryanpbrewster",
        "picture" -> StaticImageUrls.SLACK_LOGO,
        "url" -> "https://www.kifi.slack.com/archives/foo"
      )

      Json.toJson(BasicAuthor.TwitterUser("1234abcd", "Ryan Brewster", StaticImageUrls.TWITTER_LOGO, "https://www.twitter.com/ryanpbrewster/1234")) === Json.obj(
        "kind" -> "twitter",
        "id" -> "1234abcd",
        "name" -> "Ryan Brewster",
        "picture" -> StaticImageUrls.TWITTER_LOGO,
        "url" -> "https://www.twitter.com/ryanpbrewster/1234"
      )
    }
  }
}
