package com.keepit.search.tracking

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.model._
import com.keepit.search.result.{ BasicSearchHit, KifiSearchHit }
import com.keepit.social.BasicUser
import org.specs2.mutable.Specification
import play.api.libs.json._

class SearchAnalyticsTest extends Specification {

  val tags = Seq(ExternalId[Collection](), ExternalId[Collection](), ExternalId[Collection]())
  val users = Seq(
    UserFactory.user().withId(1).withName("", "").withUsername("test").get,
    UserFactory.user().withId(2).withName("", "").withUsername("test").get
  )

  "KifiHitContext" should {
    "be deserialized from a KifiSearchHit" in { // old extensions, to be deprecated

      def check(kifiHitContext: KifiHitContext, kifiSearchHit: KifiSearchHit): Unit = {
        kifiHitContext.isOwnKeep === kifiSearchHit.isMyBookmark
        kifiHitContext.isPrivate === kifiSearchHit.isPrivate
        kifiHitContext.keepCount === kifiSearchHit.count
        kifiHitContext.keepers === kifiSearchHit.users.map(_.externalId)
        kifiHitContext.tags === kifiSearchHit.bookmark.collections.toSeq.flatten
        kifiHitContext.titleMatches === kifiSearchHit.bookmark.titleMatches.length
        kifiHitContext.urlMatches === kifiSearchHit.bookmark.urlMatches.length
      }

      val hit1 = BasicSearchHit(title = Some("whatever"), url = "https://whatever", collections = Some(tags), titleMatches = Some(Seq((1, 2), (3, 4))))
      val kifiSearchHit1 = KifiSearchHit(hit1, 10, false, true, users.map(BasicUser.fromUser), 0.5.toFloat)
      val kifiHitContext1 = kifiSearchHit1.json.as[KifiHitContext]
      check(kifiHitContext1, kifiSearchHit1)

      val hit2 = BasicSearchHit(title = None, url = "https://whatever", urlMatches = Some(Seq((1, 2), (3, 4))))
      val kifiSearchHit2 = KifiSearchHit(hit2, 10, false, true, users.map(BasicUser.fromUser), 0.5.toFloat)
      val kifiHitContext2 = kifiSearchHit2.json.as[KifiHitContext]
      check(kifiHitContext2, kifiSearchHit2)
      val hit3 = BasicSearchHit(title = None, url = "https://whatever")
      val kifiSearchHit3 = KifiSearchHit(hit3, 10, false, true, users.map(BasicUser.fromUser), 0.5.toFloat)
      val kifiHitContext3 = kifiSearchHit3.json.as[KifiHitContext]
      check(kifiHitContext3, kifiSearchHit3)
      1 === 1
    }

    "be deserialized from current extension payload" in {
      val json = Json.obj(
        "isMyBookmark" -> true,
        "isPrivate" -> false,
        "count" -> 10,
        "keepers" -> users.map(_.externalId.id),

        "tags" -> tags.map(_.id),
        "title" -> Some("whatever"),
        "titleMatches" -> 3,
        "urlMatches" -> 2
      )

      val kifiHitContext = json.as[KifiHitContext]

      kifiHitContext.isOwnKeep === true
      kifiHitContext.isPrivate === false
      kifiHitContext.keepCount === 10
      kifiHitContext.keepers === users.map(_.externalId)
      kifiHitContext.tags === tags.map(_.id)
      kifiHitContext.titleMatches === 3
      kifiHitContext.urlMatches === 2
    }
  }

}
