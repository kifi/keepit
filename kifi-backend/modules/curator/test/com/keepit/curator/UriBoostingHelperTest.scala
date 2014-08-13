package com.keepit.curator

import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.time.currentDateTime
import com.keepit.curator.commanders.UriBoostingHelper
import com.keepit.curator.model.{ Keepers, SeedItem }
import com.keepit.model.{ NormalizedURI, User }
import org.specs2.mutable.Specification
import com.keepit.common.time._

class UriBoostingHelperTest extends Specification with CuratorTestInjector {

  private def makeSeedItems(): Seq[SeedItem] = {
    val seedItem1 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](1),
      url = """https://twitter.com/RoseHulmanAlums/status/494491541856337920""",
      seq = SequenceNumber[SeedItem](1), priorScore = None, timesKept = 1000, lastSeen = currentDateTime, keepers = Keepers.TooMany, discoverable = true)
    val seedItem2 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](2),
      url = """https://www.linkedin.com/profile/view?id=94705338&trk=nav_responsive_tab_profile_pic""",
      seq = SequenceNumber[SeedItem](2), priorScore = None, timesKept = 10, lastSeen = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](3))), discoverable = true)
    val seedItem3 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](3),
      url = """https://www.facebook.com/photo.php?v=290557561103376""",
      seq = SequenceNumber[SeedItem](3), priorScore = None, timesKept = 93, lastSeen = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](2))), discoverable = true)
    val seedItem4 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](4),
      url = "http://www.wikihow.com/Use-Twitter",
      seq = SequenceNumber[SeedItem](4), priorScore = None, timesKept = 20, lastSeen = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    val seedItem5 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](5),
      url = "http://techcrunch.com/2014/07/30/xiaomis-one-more-thing/",
      seq = SequenceNumber[SeedItem](5), priorScore = None, timesKept = 20, lastSeen = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    val seedItem6 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](5),
      url = "http://en.wikipedia.org/wiki/Byzantine_fault_tolerance",
      seq = SequenceNumber[SeedItem](6), priorScore = None, timesKept = 20, lastSeen = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    val seedItem7 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](5),
      url = "https://mail.google.com/mail/u/0/#inbox",
      seq = SequenceNumber[SeedItem](7), priorScore = None, timesKept = 20, lastSeen = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)

    seedItem1 :: seedItem2 :: seedItem3 :: seedItem4 :: seedItem5 :: seedItem6 :: seedItem7 :: Nil
  }

  "UriBoostingHelperTest" should {
    "boost to downgrade some urls" in {
      withInjector() { implicit injector =>
        val uriBoostingHelper = inject[UriBoostingHelper]
        val multipliedSeedItems = uriBoostingHelper(makeSeedItems)
        multipliedSeedItems(0).multiplier === 0.01f
        multipliedSeedItems(1).multiplier === 0.01f
        multipliedSeedItems(2).multiplier === 0.001f
        multipliedSeedItems(3).multiplier === 1.0f
        multipliedSeedItems(4).multiplier === 1.2f
        multipliedSeedItems(5).multiplier === 0.1f
        multipliedSeedItems(6).multiplier === 0.001f
      }
    }
  }
}
