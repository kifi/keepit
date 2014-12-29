package com.keepit.common.net

import org.specs2.mutable.Specification
import java.net.{ URI => JavaURI }

class URITest extends Specification {
  "URI" should {
    "parse empty url" in {
      URI.parse("http://").isFailure === true
      URI.parse("https://").isFailure === true
      URI.parse("javascript://").isFailure === false
    }

    "don't parse" in {
      URI.parse("http://push notification to client web browser/?+http://www.reddit.com/r/laravel/comments/2e6hgz/push_notification_to_client_web_browser/").isFailure === true
    }

    "bad fragment" in {
      URI.parse("http://www.facebook.com/justcreativedesign#!/posted.php?id=12415723757&share_id=125818997465306&comments=1#s125818997465306").get.toString() ===
        "http://www.facebook.com/justcreativedesign#!/posted.php?id=12415723757&share_id=125818997465306&comments=1%23s125818997465306"
    }

    "parse URLs" in {
      URI.parse("http://google.com/").get.host.get.name === "google.com"
      URI.parse("https://sub.domain.com").get.host.get.name === "sub.domain.com"
      URI.parse("http://bing.com/?q=1").get.query.get.params === Seq(Param("q", Some("1")))
      URI.parse("http://cr.com/SchedPdf.aspx?locIds={99D3-41B6-9852}&dir=asc").get.query.get.params ===
        Seq(Param("dir", Some("asc")), Param("locIds", Some("%7B99D3-41B6-9852%7D")))
      URI.parse("http://da.seek.com/d/PERK_[PerkinElmer_Optoelec]_SU405-2.html").get.path ===
        Some("/d/PERK_%5BPerkinElmer_Optoelec%5D_SU405-2.html")
      URI.parse("http://scala.org/api/index.html#scala.reflect.api.Universe@Type>:Null<:Types.this.TypeApi").get.fragment ===
        Some("scala.reflect.api.Universe@Type%3E:Null%3C:Types.this.TypeApi")
      URI.parse("http://www.walmart.com/browse/tvs/3_1/?facet=tv_screen_size_range%3A60``+%26+Larger").get.query.get.params ===
        Seq(Param("facet", Some("tv_screen_size_range:60%60%60+&+Larger")))
      URI.parse("""https://mint.com/inv?accountId=42#location%3A%7B"accountId"%3A"0",+"tab"%3A0%7D""").get.fragment ===
        Some("location:%7B%22accountId%22:%220%22,+%22tab%22:0%7D")
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
          query.get.params === Seq( // alphabetized by parameter name
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
          path === Some("/torrent/7998570/torrent_that_previously_failed_2560_X_1600_%5BSet_7%5D")
          query === None
          fragment === None
        case _ => failure
      }
      success
    }
    //    "throw URISyntaxException upon .get after failed parse" in {
    //      URI.parse("http://ho\tst").get must throwA[java.net.URISyntaxException]
    //    }
    "compare equal to equal URIs" in {
      val uri1 = URI.parse("http://google.com/").get
      val uri2 = URI.parse("http://google.com/").get
      val uri3 = URI.parse("http://google.com").get
      val uri4 = URI.parse("http://www.42go.com/team.html").get
      val uri5 = URI.parse("HTTP://WWW.42GO.COM/team.html").get
      val uri6 = URI.parse("http://www.42go.com/TEAM.html").get
      val uri7 = URI.parse("http://www.linkedin.com/?trk=hb-0-h-logo").get
      val uri8 = URI.parse("http://www.linkedin.com/?trk=hb-0-h-logo").get
      val uri9 = URI.parse("http://www.linkedin.com/?trk=HB-0-H-LOGO").get

      uri1 === uri2
      uri2 !== uri3
      uri4 === uri5
      uri5 !== uri6
      uri7 === uri8
      uri8 !== uri9
    }

    "allow trailing dots after the domain name" in {
      val singleDot = URI.parse("http://www.42go.com./team.html").get
      val multipleDots = URI.parse("http://www.42go.com..../team.html").get

      singleDot.toString === "http://www.42go.com./team.html"
      singleDot.host === Some(Host("", "com", "42go", "www"))
      multipleDots.toString === "http://www.42go.com..../team.html"
      multipleDots.host === Some(Host("", "", "", "", "com", "42go", "www"))
    }

    "isRelative" in {
      URI.isRelative("user/index.php") === true
      URI.isRelative("/user/index.php") === true
      URI.isRelative("../user/index.php") === true
    }

    "absoluteUrl" in {
      URI.absoluteUrl("http://www.kifi.com", "http://") === None
      URI.absoluteUrl("http://www.kifi.com", "welcome.html").get === "http://www.kifi.com/welcome.html"
      URI.absoluteUrl("http://www.kifi.com", "/welcome.html").get === "http://www.kifi.com/welcome.html"
      URI.absoluteUrl("http://www.kifi.com/home/", "welcome.html").get === "http://www.kifi.com/home/welcome.html"
      URI.absoluteUrl("http://www.kifi.com/index.html", "welcome.html").get === "http://www.kifi.com/welcome.html"
      URI.absoluteUrl("http://www.kifi.com/index.html#hi_there", "welcome.html").get === "http://www.kifi.com/welcome.html"
      URI.absoluteUrl("http://www.kifi.com/index.html", "#welcome").get === "http://www.kifi.com/index.html#welcome"
      URI.absoluteUrl("http://www.kifi.com:9000", "welcome.html").get === "http://www.kifi.com:9000/welcome.html"
      URI.absoluteUrl("https://www.kifi.com:9000/home/foo.html", "welcome.html").get === "https://www.kifi.com:9000/home/welcome.html"
      URI.absoluteUrl("http://www.kifi.com/home/foo.html", "/welcome.html").get === "http://www.kifi.com/welcome.html"
      URI.absoluteUrl("http://www.kifi.com/home/foo.html", "/welcome.html#bar").get === "http://www.kifi.com/welcome.html#bar"
      URI.absoluteUrl("http://www.kifi.com/home/foo.html", "../welcome.html").get === "http://www.kifi.com/welcome.html"
      URI.absoluteUrl("http://www.kifi.com/home/foo.html", "./../welcome.html").get === "http://www.kifi.com/welcome.html"
      URI.absoluteUrl("http://www.kifi.com/home/", "./../welcome.html").get === "http://www.kifi.com/welcome.html"
      URI.absoluteUrl("http://www.kifi.com/home/", "../welcome.html").get === "http://www.kifi.com/welcome.html"
      URI.absoluteUrl("http://www.kifi.com/home", "../welcome.html").get === "http://www.kifi.com/welcome.html"
      URI.absoluteUrl("http://www.kifi.com/home/path2/foo.html", "./../../welcome.html").get === "http://www.kifi.com/welcome.html"
      URI.absoluteUrl("http://www.kifi.com/home/path2/foo.html", ".././../welcome.html").get === "http://www.kifi.com/welcome.html"
      URI.absoluteUrl("http://www.kifi.com/home/path2/foo.html", "../../welcome.html").get === "http://www.kifi.com/welcome.html"
      URI.absoluteUrl("http://www.kifi.com/home/foo.html#fragment", "../welcome.html").get === "http://www.kifi.com/welcome.html"
      URI.absoluteUrl("http://www.kifi.com/home/foo.html#fragment", "welcome.html").get === "http://www.kifi.com/home/welcome.html"
      URI.absoluteUrl("http://www.kifi.com/home/foo.html", "./welcome.html").get === "http://www.kifi.com/home/welcome.html"
      URI.absoluteUrl("http://www.kifi.com/home/foo.html", "compmodels.aspx?modelid=730310").get === "http://www.kifi.com/home/compmodels.aspx?modelid=730310"
    }

    "be compatible with Java URI" in {
      val urls = Seq(
        "http://cr.com/SchedPdf.aspx?locIds={99D3-41B6-9852}&dir=asc",
        "http://da.seek.com/d/PERK_[PerkinElmer_Optoelec]_SU405-2.html",
        "http://scala.org/api/index.html#scala.reflect.api.Universe@Type>:Null<:Types.this.TypeApi",
        "http://www.walmart.com/browse/tvs/3_1/?facet=tv_screen_size_range%3A60``+%26+Larger",
        """https://mint.com/inv?accountId=42#location%3A%7B"accountId"%3A"0",+"tab"%3A0%7D""",
        "http://www.liveleak.com/view?comments=1\\",
        "http://premium.nba.com/pr/leaguepass/app/2012/console.html?debug=false&type=lp&TinedSid=Gaa419b-25665208-1262918951531-1&nsfg=1355463185|billing.lpbchoice_LAL_LAC_NYK_MIA_OKC^billing.lpbchoice^giBJ5TL8HJT8eLc6&retryCount=3",
        "http://finance.yahoo.com/q?s=^dji",
        "http://somerandomtorrentsi.te/torrent/7998570/torrent_that_previously_failed_2560_X_1600_[Set_7]",
        "http://www.cascadecard.com/<%25=this.SiteBasePath%25>",
        "http://www.columnfivemedia.com/{{getAbsoluteURL()}}",
        "http://www.amazon.com/dp/B00I0LGU8M?ascsubtag=%5Btype|link%5BpostId|1542311578%5Basin|B00I0LGU8M%5BauthorId|5727177402741770316&tag=lifehackeramzn-20",
        "http://www.filestube.com/cWA7UKlGrkprbJenUr7ADj/[Wii]Ea-Sports-Nba-jam-WarezLeech-net.html"
      )

      urls.foreach { url =>
        val parsedUrl = URI.parse(url).get.toString()
        JavaURI.create(parsedUrl).toString === parsedUrl
      }

      "All Good" === "All Good"
    }
  }
}
