package com.keepit.common.net

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class URITest extends Specification {
  "URI" should {
    "parse URLs" in {
      URI.parse("http://google.com/").get.host.get.name === "google.com"
      URI.parse("https://sub.domain.com").get.host.get.name === "sub.domain.com"
      URI.parse("http://bing.com/?q=1").get.query.get.params === Seq(Param("q", Some("1")))
      URI.parse("http://cr.com/SchedPdf.aspx?locIds={99D3-41B6-9852}&dir=asc").get.query.get.params ===
        Seq(Param("dir", Some("asc")), Param("locIds", Some("%7B99D3-41B6-9852%7D")))
      URI.parse("http://da.seek.com/d/PERK_[PerkinElmer_Optoelec]_SU405-2.html").get.path ===
        Some("/d/PERK_[PerkinElmer_Optoelec]_SU405-2.html")
      URI.parse("http://scala.org/api/index.html#scala.reflect.api.Universe@Type>:Null<:Types.this.TypeApi").get.fragment ===
        Some("scala.reflect.api.Universe%40Type%3E%3ANull%3C%3ATypes.this.TypeApi")
    }
    "parse URLs via unapply" in {
      "http://premium.nba.com/pr/leaguepass/app/2012/console.html?debug=false&type=lp&TinedSid=Gaa419b-25665208-1262918951531-1&nsfg=1355463185|billing.lpbchoice_LAL_LAC_NYK_MIA_OKC^billing.lpbchoice^giBJ5TL8HJT8eLc6&retryCount=3" match {
        case URI(scheme, userInfo, host, port, path, query, fragment) =>
          scheme === Some("http")
          userInfo === None
          host.get.domain === Seq("com", "nba", "premium")
          port === -1
          path === Some("/pr/leaguepass/app/2012/console.html")
          query.get.params === Seq(  // alphabetized by parameter name
            Param("TinedSid", Some("Gaa419b-25665208-1262918951531-1")),
            Param("debug", Some("false")),
            Param("nsfg", Some("1355463185%7Cbilling.lpbchoice_LAL_LAC_NYK_MIA_OKC%5Ebilling.lpbchoice%5EgiBJ5TL8HJT8eLc6")),
            Param("retryCount", Some("3")),
            Param("type", Some("lp")))
          fragment === None
        case _ => failure
      }
      "http://finance.yahoo.com/q?s=^dji" match {
        case URI(scheme, userInfo, host, port, path, query, fragment) =>
          scheme === Some("http")
          userInfo === None
          host.get.domain === Seq("com", "yahoo", "finance")
          port === -1
          path === Some("/q")
          query.get.params === Seq(Param("s", Some("%5Edji")))
          fragment === None
        case _ => failure
      }
      "http://somerandomtorrentsi.te/torrent/7998570/torrent_that_previously_failed_2560_X_1600_[Set_7]" match {
        case URI(scheme, userInfo, host, port, path, query, fragment) =>
          scheme === Some("http")
          userInfo === None
          host.get.domain === Seq("te", "somerandomtorrentsi")
          port === -1
          path === Some("/torrent/7998570/torrent_that_previously_failed_2560_X_1600_[Set_7]")
          query === None
          fragment === None
        case _ => failure
      }
      success
    }
    "throw URISyntaxException upon .get after failed parse" in {
      URI.parse("`").get must throwA[java.net.URISyntaxException]
    }
  }
}
