package com.keepit.curator

import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.time.currentDateTime
import com.keepit.curator.commanders.UriWeightingHelper
import com.keepit.curator.model.{ Keepers, SeedItem }
import com.keepit.model.{ NormalizedURI, User }
import org.specs2.mutable.Specification
import com.keepit.common.time._

class UriWeightingHelperTest extends Specification with CuratorTestInjector {

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
    val seedItem6 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](6),
      url = "http://en.wikipedia.org/wiki/Byzantine_fault_tolerance",
      seq = SequenceNumber[SeedItem](6), priorScore = None, timesKept = 20, lastSeen = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    val seedItem7 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](7),
      url = "https://mail.google.com/mail/u/0/#inbox",
      seq = SequenceNumber[SeedItem](7), priorScore = None, timesKept = 20, lastSeen = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    val seedItem8 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](8),
      url = "http://topics.nytimes.com/top/news/business/companies/twitter/index.html?inline=nyt-org",
      seq = SequenceNumber[SeedItem](8), priorScore = None, timesKept = 20, lastSeen = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    val seedItem9 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](9),
      url = "http://www.wired.com/2014/04/perfect-facebook-feed/",
      seq = SequenceNumber[SeedItem](9), priorScore = None, timesKept = 20, lastSeen = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    val seedItem10 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](10),
      url = "http://www.facebook.commm/photo",
      seq = SequenceNumber[SeedItem](10), priorScore = None, timesKept = 20, lastSeen = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    val seedItem11 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](11),
      url = "http://facebook.com/???",
      seq = SequenceNumber[SeedItem](11), priorScore = None, timesKept = 20, lastSeen = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    val seedItem12 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](12),
      url = "http://facebook.com/kifi42",
      seq = SequenceNumber[SeedItem](12), priorScore = None, timesKept = 20, lastSeen = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    val seedItem13 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](13),
      url = "https://engineering.linkedin.com/mobile/linkedin-connected-engineering-pre-meeting-intelligence",
      seq = SequenceNumber[SeedItem](13), priorScore = None, timesKept = 20, lastSeen = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    val seedItem14 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](14),
      url = "https://blog.twitter.com/2014/scalingtwitter-in-dublin-and-the-uk",
      seq = SequenceNumber[SeedItem](14), priorScore = None, timesKept = 20, lastSeen = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    val seedItem15 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](15),
      url = "https://code.facebook.com/posts/313033472212144/debugging-file-corruption-on-ios/",
      seq = SequenceNumber[SeedItem](15), priorScore = None, timesKept = 20, lastSeen = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)

    seedItem1 :: seedItem2 :: seedItem3 :: seedItem4 :: seedItem5 :: seedItem6 :: seedItem7 :: seedItem8 :: seedItem9 :: seedItem10 :: seedItem11 :: seedItem12 ::
      seedItem13 :: seedItem14 :: seedItem15 :: Nil
  }

  "UriBoostingHelperTest" should {
    "boost or penalize some urls" in {
      withInjector() { implicit injector =>
        val uriBoostingHelper = inject[UriWeightingHelper]
        val multipliedSeedItems = uriBoostingHelper(makeSeedItems)
        multipliedSeedItems(0).multiplier === 0.01f
        multipliedSeedItems(1).multiplier === 0.01f
        multipliedSeedItems(2).multiplier === 0.001f
        multipliedSeedItems(3).multiplier === 1.0f
        multipliedSeedItems(4).multiplier === 1.2f
        multipliedSeedItems(5).multiplier === 0.1f
        multipliedSeedItems(6).multiplier === 0.001f
        multipliedSeedItems(7).multiplier === 1.0f
        multipliedSeedItems(8).multiplier === 1.0f
        multipliedSeedItems(9).multiplier === 1.0f
        multipliedSeedItems(10).multiplier === 0.001f
        multipliedSeedItems(11).multiplier === 0.0012f
        multipliedSeedItems(12).multiplier === 0.011f
        multipliedSeedItems(13).multiplier === 0.011f
        multipliedSeedItems(14).multiplier === 0.0011000001f
      }
    }
  }
}
