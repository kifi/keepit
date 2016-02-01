package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.social.twitter.{ RawTweet, TwitterHandle }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json._

class KeepSourceAttributionTest extends Specification with ShoeboxTestInjector {
  val tweetStr = """{"source":"<a href=\"http://twitter.com/download/iphone\" rel=\"nofollow\">Twitter for iPhone</a>","entities":{"user_mentions":[{"name":"Jonas Bonér","screen_name":"jboner","indices":[3,10],"id_str":"18160154","id":18160154}],"media":[],"hashtags":[{"text":"akka","indices":[104,109]},{"text":"typesafe","indices":[110,119]}],"urls":[{"indices":[80,103],"url":"https://t.co/ineugCd9P7","expanded_url":"https://typesafe.com/blog/akka-roadmap-update-2014","display_url":"typesafe.com/blog/akka-road…"}]},"geo":{},"id_str":"505809542656303104","text":"RT @jboner: Don't miss the new Akka roadmap writeup. Exciting things coming up. https://t.co/ineugCd9P7 #akka #typesafe","retweeted_status":{"source":"<a href=\"http://twitter.com\" rel=\"nofollow\">Twitter Web Client</a>","entities":{"user_mentions":[],"media":[],"hashtags":[{"text":"akka","indices":[92,97]},{"text":"typesafe","indices":[98,107]}],"urls":[{"indices":[68,91],"url":"https://t.co/ineugCd9P7","expanded_url":"https://typesafe.com/blog/akka-roadmap-update-2014","display_url":"typesafe.com/blog/akka-road…"}]},"geo":{},"id_str":"505065357376503808","text":"Don't miss the new Akka roadmap writeup. Exciting things coming up. https://t.co/ineugCd9P7 #akka #typesafe","id":505065357376503808,"created_at":"2014-08-28 18:52:19 +0000","user":{"name":"Jonas Bonér","screen_name":"jboner","protected":false,"id_str":"18160154","profile_image_url_https":"https://pbs.twimg.com/profile_images/540462338705342464/K42LvN4H_normal.jpeg","id":18160154,"verified":false}},"id":505809542656303104,"created_at":"2014-08-30 20:09:27 +0000","user":{"name":"Andrew Conner","screen_name":"connerdelights","protected":false,"id_str":"610392096","profile_image_url_https":"https://pbs.twimg.com/profile_images/496729766779580416/ePxivSZA_normal.png","id":610392096,"verified":false}}"""
  val tweetJs = Json.parse(tweetStr)
  val connerdelights = TwitterHandle("connerdelights")

  "KeepSourceAttribution" should {
    "twitter parsing works" in {
      val tweet = tweetJs.as[RawTweet]
      tweet.id.id === 505809542656303104L
      tweet.user.screenName === connerdelights
      tweet.getUrl === "https://twitter.com/connerdelights/status/505809542656303104"
    }

    "generate from Rawkeep" in {
      val rawKeep = RawKeep(userId = Id[User](1),
        url = "https://twitter.com/connerdelights/status/505809542656303104",
        source = KeepSource.twitterFileImport,
        originalJson = Some(tweetJs),
        libraryId = None)

      val attr = RawKeep.extractKeepSourceAttribution(rawKeep)
      attr.get === TwitterAttribution(RawTweet.format.reads(tweetJs).get)

      val rawKeep2 = rawKeep.copy(originalJson = Some(JsString("{}")))
      RawKeep.extractKeepSourceAttribution(rawKeep2) === None

      val rawKeep3 = rawKeep.copy(source = KeepSource.bookmarkFileImport)
      RawKeep.extractKeepSourceAttribution(rawKeep3) === None
    }

    "twitter source attribution is backward compatible" in {
      val attr = TwitterAttribution(tweetJs.as[RawTweet])
      val obj = SourceAttribution.deprecatedWrites.writes((attr, None))
      (obj \ "twitter" \ "idString").as[String] === "505809542656303104"
      (obj \ "twitter" \ "screenName").as[TwitterHandle] === connerdelights
    }
  }
}

