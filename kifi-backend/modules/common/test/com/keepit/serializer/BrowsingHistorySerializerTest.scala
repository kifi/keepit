package com.keepit.serializer

import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import securesocial.core._
import com.keepit.model.{BrowsingHistory, User}
import com.keepit.common.db._

class BrowsingHistorySerializerTest extends Specification {

  "BrowsingHistorySerializer" should {
    "do a basic serialization flow" in {

      val filter = Array.fill[Byte](10)(0) ++ Array.fill[Byte](10)(1) ++ Array.fill[Byte](3047)(2)

      val browsingHistory = BrowsingHistory(
        id = Some(Id[BrowsingHistory](1)),
        userId = Id[User](1),
        tableSize = 3067,
        filter = filter,
        numHashFuncs = 5,
        minHits = 1,
        updatesCount = 42
      )

      val serializer = BrowsingHistoryBinarySerializer.browsingHistoryBinarySerializer
      val serializedHistory = serializer.writes(Some(browsingHistory))

      val deserializedHistory = serializer.reads(serializedHistory).get

      deserializedHistory.filter.deep === filter.deep
      deserializedHistory.copy(filter = filter) === browsingHistory
    }
    "handle edge cases like The Edge" in {

      val filter1 = Array.fill[Byte](0)(0)

      val browsingHistory = BrowsingHistory(
        id = Some(Id[BrowsingHistory](1)),
        userId = Id[User](1),
        tableSize = 0,
        filter = filter1,
        numHashFuncs = 5,
        minHits = 1,
        updatesCount = 42
      )
      val serializer = BrowsingHistoryBinarySerializer.browsingHistoryBinarySerializer
      val serializedHistory = serializer.writes(Some(browsingHistory))
      serializer.reads(serializedHistory).get must throwA[AssertionError]
    }
    "Fail appropriately" in {

      val filter1 = Array.fill[Byte](10)(0)

      val browsingHistory = BrowsingHistory(
        id = Some(Id[BrowsingHistory](1)),
        userId = Id[User](1),
        tableSize = 30,
        filter = filter1,
        numHashFuncs = 5,
        minHits = 1,
        updatesCount = 42
      )
      val serializer = BrowsingHistoryBinarySerializer.browsingHistoryBinarySerializer
      serializer.writes(Some(browsingHistory)) must throwA[AssertionError]
    }
  }

}
