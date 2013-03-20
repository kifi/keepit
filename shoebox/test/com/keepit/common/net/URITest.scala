package com.keepit.common.net

import org.specs2.mutable.Specification

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
      URI.parse("http://www.walmart.com/browse/tvs/3_1/?facet=tv_screen_size_range%3A60``+%26+Larger").get.query.get.params ===
        Seq(Param("facet", Some("tv_screen_size_range%3A60%60%60+%26+Larger")))
      URI.parse("""https://mint.com/inv?accountId=42#location%3A%7B"accountId"%3A"0",+"tab"%3A0%7D""").get.fragment ===
        Some("location%3A%7B%22accountId%22%3A%220%22%2C+%22tab%22%3A0%7D")
      URI.parse("http://4945457844119005844_911a106f1584b002d8018a27243b8aa2829655c4.blogspot.com").get.host.get.domain ===
        Seq("com", "blogspot", "4945457844119005844_911a106f1584b002d8018a27243b8aa2829655c4")
      URI.parse("http://foo+bar").get.host.get.domain === Seq("foo+bar")
      URI.parse("http://www.liveleak.com/view?comments=1\\").get.query.get.params === Seq(Param("comments", Some("1%5C")))
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
      URI.parse("http://ho\tst").get must throwA[java.net.URISyntaxException]
    }
  }
}
