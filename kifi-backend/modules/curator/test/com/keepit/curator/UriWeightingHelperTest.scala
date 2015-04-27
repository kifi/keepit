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
    //kifi
    val seedItem1 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](1),
      url = """https://www.kifi.com/tag/d5d3e575-c180-461d-a69d-0a9d90c9d3e4""",
      seq = SequenceNumber[SeedItem](1), priorScore = None, timesKept = 1000, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.TooMany, discoverable = true)
    //42go
    val seedItem2 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](2),
      url = """https://www.42go.com""",
      seq = SequenceNumber[SeedItem](2), priorScore = None, timesKept = 10, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](3))), discoverable = true)
    //wiki
    val seedItem3 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](3),
      url = """http://en.wikipedia.org/wiki/Poland""",
      seq = SequenceNumber[SeedItem](3), priorScore = None, timesKept = 93, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](2))), discoverable = true)
    //amazon
    val seedItem4 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](4),
      url = "http://www.amazon.com/gp/product/B002NX13LC/ref=s9_simh_gw_p421_d12_i1?pf_rd_m=ATVPDKIKX0DER&pf_rd_s=center-2&pf_rd_r=1F280V4EQJ708K7PEDWQ&pf_rd_t=101&pf_rd_p=1688200382&pf_rd_i=507846",
      seq = SequenceNumber[SeedItem](4), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    //ebay
    val seedItem5 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](5),
      url = "http://www.ebay.com/cln/ebaydealseditor/Best-of-Fashion/111049230015",
      seq = SequenceNumber[SeedItem](5), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    //walmart
    val seedItem6 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](6),
      url = "http://www.walmart.com/ip/Visa-50-Gift-Card/16513375",
      seq = SequenceNumber[SeedItem](6), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    //etsy
    val seedItem7 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](7),
      url = "https://www.etsy.com/listing/201179611/lafont-vintage-sunglasses-authentic-jean?ref=br_feed_2&br_feed_tlp=men",
      seq = SequenceNumber[SeedItem](7), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    //google calendar
    val seedItem8 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](8),
      url = "https://www.google.com/calendar/render",
      seq = SequenceNumber[SeedItem](8), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    //fedex
    val seedItem9 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](9),
      url = "https://www.fedex.com/fedextrack/html/oldindex.html?cntry_code=ai&locale=en_AI&tracknumbers=770650649770",
      seq = SequenceNumber[SeedItem](9), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    //ups
    val seedItem10 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](10),
      url = "https://www.ups.com/mrd/promodiscount?loc=en_US&promoCd=BI6ZNRZ71&WT.ac=UPS_HP_SmallBusiness0814v1_Mrktg_P1_U1",
      seq = SequenceNumber[SeedItem](10), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    //usps
    val seedItem11 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](11),
      url = "https://www.usps.com/welcome.htm",
      seq = SequenceNumber[SeedItem](11), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    //gmail
    val seedItem12 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](12),
      url = "https://mail.google.com/mail/u/0/#inbox",
      seq = SequenceNumber[SeedItem](12), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    //groupon
    val seedItem13 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](13),
      url = "https://www.groupon.com/goods?category=basics",
      seq = SequenceNumber[SeedItem](13), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    //linkedin profile
    val seedItem14 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](14),
      url = "https://www.linkedin.com/profile/view?id=140829929&authType=name&authToken=U4lJ&trk=nmp_rec_act_profile_photo",
      seq = SequenceNumber[SeedItem](14), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    //twitter
    val seedItem15 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](15),
      url = "https://twitter.com/hashtag/hyperlapse?src=tren",
      seq = SequenceNumber[SeedItem](15), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    //facebook
    val seedItem16 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](16),
      url = "https://www.facebook.com/gopro?fref=nf",
      seq = SequenceNumber[SeedItem](16), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    //dropbox
    val seedItem17 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](17),
      url = "https://www.dropbox.com/links",
      seq = SequenceNumber[SeedItem](17), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    //craigslist
    val seedItem18 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](18),
      url = "https://sfbay.craigslist.org/eby/sub/4630730291.html",
      seq = SequenceNumber[SeedItem](18), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    //support page
    val seedItem19 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](19),
      url = "https://developers.facebook.com/support/",
      seq = SequenceNumber[SeedItem](19), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    //google drive, docs and plus
    val seedItem20 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](20),
      url = "https://drive.google.com/a/kifi.com/?urp=https://docs.google.com/&pli=1&ddrp=1#my-drive",
      seq = SequenceNumber[SeedItem](20), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    //google search, images, videos and maps
    val seedItem21 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](21),
      url = "https://www.google.com/search?site=&tbm=isch&source=hp&biw=1251&bih=756&q=fdsfdas&oq=fdsfdas&gs_l=img.12..0i10i24.934.1394.0.2712.7.5.0.0.0.0.217.217.2-1.1.0....0...1ac.1.52.img..6.1.217.Rx-ZPDpbYf4&gws_rd=ssl#q=random&tbm=isch",
      seq = SequenceNumber[SeedItem](21), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)

    //bing search, images, videos and maps
    val seedItem22 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](22),
      url = "http://www.bing.com/images/search?q=random&go=Submit&qs=n&form=QBIR&pq=random&sc=0-0&sp=-1&sk=",
      seq = SequenceNumber[SeedItem](22), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)

    //yahoo search, images, videos, answer and maps
    val seedItem23 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](23),
      url = "https://search.yahoo.com/search;_ylt=AnnOyPSfJUbaeCx8rU6tPQebvZx4?p=fdsfda&toggle=1&cop=mss&ei=UTF-8&fr=yfp-t-250&fp=1",
      seq = SequenceNumber[SeedItem](23), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)

    //Any home page
    val seedItem24 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](24),
      url = "http://www.thoroughbreadpastry.com/index.php/Catering/index",
      seq = SequenceNumber[SeedItem](24), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)

    //tripadvisor
    val seedItem25 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](25),
      url = "http://www.tripadvisor.com/TravelersChoice",
      seq = SequenceNumber[SeedItem](25), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)

    //medium
    val seedItem26 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](26),
      url = "https://medium.com/@tjalve/the-story-behind-betech-9e8f2686f489",
      seq = SequenceNumber[SeedItem](26), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)

    //longreads
    val seedItem27 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](27),
      url = "http://blog.longreads.com/2014/08/26/mango-mango-a-family-a-fruit-stand-and-survival-on-4-50-a-day/",
      seq = SequenceNumber[SeedItem](27), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)

    //Videos on Youtube
    val seedItem28 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](28),
      url = "https://www.youtube.com/watch?v=NedZT-tIBLA",
      seq = SequenceNumber[SeedItem](28), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)

    Seq(seedItem1, seedItem2, seedItem3, seedItem4, seedItem5, seedItem6, seedItem7, seedItem8, seedItem9, seedItem10, seedItem11, seedItem12,
      seedItem13, seedItem14, seedItem15, seedItem16, seedItem17, seedItem18, seedItem19, seedItem20, seedItem21, seedItem22, seedItem23, seedItem24,
      seedItem25, seedItem26, seedItem27, seedItem28)
  }

  "UriBoostingHelperTest" should {
    "boost or penalize some urls" in {
      withInjector() { implicit injector =>
        val uriBoostingHelper = inject[UriWeightingHelper]
        val multipliedSeedItems = uriBoostingHelper(makeSeedItems)
        multipliedSeedItems(0).multiplier === 0.0f
        multipliedSeedItems(1).multiplier === 0.0f
        multipliedSeedItems(2).multiplier === 0.0f
        multipliedSeedItems(3).multiplier === 0.0f
        multipliedSeedItems(4).multiplier === 0.0f
        multipliedSeedItems(5).multiplier === 0.0f
        multipliedSeedItems(6).multiplier === 0.0f
        multipliedSeedItems(7).multiplier === 0.0f
        multipliedSeedItems(8).multiplier === 0.0f
        multipliedSeedItems(9).multiplier === 0.0f
        multipliedSeedItems(10).multiplier === 0.0f
        multipliedSeedItems(11).multiplier === 0.0f
        multipliedSeedItems(12).multiplier === 0.5f
        multipliedSeedItems(13).multiplier === 0.5f
        multipliedSeedItems(14).multiplier === 0.5f
        multipliedSeedItems(15).multiplier === 0.5f
        multipliedSeedItems(16).multiplier === 0.5f
        multipliedSeedItems(17).multiplier === 0.5f
        multipliedSeedItems(18).multiplier === 0.5f
        multipliedSeedItems(19).multiplier === 0.5f
        multipliedSeedItems(20).multiplier === 0.5f
        multipliedSeedItems(21).multiplier === 0.5f
        multipliedSeedItems(22).multiplier === 0.5f
        multipliedSeedItems(23).multiplier === 0.8f
        multipliedSeedItems(24).multiplier === 1.1f
        multipliedSeedItems(25).multiplier === 1.1f
        multipliedSeedItems(26).multiplier === 1.1f
        multipliedSeedItems(27).multiplier === 1.1f
      }
    }
  }
}
