package com.keepit.common.net

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class URITest extends Specification {

  "URI" should {

    "parse urls" in {
      URI.parse("http://google.com/").isSuccess === true
      URI.parse("http://bing.com/?q=1").isSuccess === true
      URI.parse("https://sub.domain.com").isSuccess === true
      URI.unapply("http://google.com/").get._3.get.name === "google.com"
    }
    "handle edge cases on unapply" in {
      URI.unapply("http://premium.nba.com/pr/leaguepass/app/2012/console.html?debug=false&type=lp&TinedSid=Gaa419b-25665208-1262918951531-1&nsfg=1355463185|billing.lpbchoice_LAL_LAC_NYK_MIA_OKC^billing.lpbchoice^giBJ5TL8HJT8eLc6&retryCount=3").isDefined == true
      URI.unapply("http://finance.yahoo.com/q?s=^dji").isDefined == true
      URI.unapply("http://somerandomtorrentsi.te/torrent/7998570/torrent_that_previously_failed_2560_X_1600_[Set_7]").isDefined == true
    }
  }
}
