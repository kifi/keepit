package com.keepit.search.tracking

import com.keepit.common.db.{ ExternalId }
import com.keepit.model._
import org.specs2.mutable.Specification
import play.api.libs.json._

class SearchAnalyticsTest extends Specification {

  val tags = Seq(ExternalId[Collection](), ExternalId[Collection](), ExternalId[Collection]())
  val users = Seq(
    UserFactory.user().withId(1).withName("", "").withUsername("test").get,
    UserFactory.user().withId(2).withName("", "").withUsername("test").get
  )

  "KifiHitContext" should {

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
