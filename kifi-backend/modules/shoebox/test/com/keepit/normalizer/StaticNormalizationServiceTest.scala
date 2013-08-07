package com.keepit.normalizer

import org.specs2.mutable.Specification

class StaticNormalizationServiceTest extends Specification {

  "StaticNormalizationService" should {

    "upcase scheme and host" in {
      StaticNormalizationService.normalize("HTTP://KeepItFindIt.com") === "http://keepitfindit.com"
    }

    "remove port 80" in {
      StaticNormalizationService.normalize("HTTP://KeepItFindIt.com:8080") === "http://keepitfindit.com:8080"
      StaticNormalizationService.normalize("HTTP://KeepItFindIt.com:80") === "http://keepitfindit.com"
    }

    "remove fragment" in {
      StaticNormalizationService.normalize("http://keepitfindit.com/path#abc") === "http://keepitfindit.com/path"
      StaticNormalizationService.normalize("http://keepitfindit.com/page?xyz=123#abc") === "http://keepitfindit.com/page?xyz=123"
      //StaticNormalizationService.normalize("http://www.foo.com/foo.html#something?x=y") === "http://www.foo.com/foo.html?x=y"
    }

    "remove default pages (index.html, etc.)" in {
      StaticNormalizationService.normalize("http://www.example.com/index.html") === "http://www.example.com"
      StaticNormalizationService.normalize("http://www.example.com/A/index.html") === "http://www.example.com/A/"
      StaticNormalizationService.normalize("http://www.example.com/A/B/index.html") === "http://www.example.com/A/B/"
      StaticNormalizationService.normalize("http://keepitfindit.com/index.html#abc") === "http://keepitfindit.com"
      StaticNormalizationService.normalize("http://keepitfindit.com/index.html?a=b") === "http://keepitfindit.com/?a=b"

      // taken from https://svn.apache.org/repos/asf/nutch/trunk/src/plugin/urlnormalizer-regex/sample/regex-normalize-default.test
      // and modified
      StaticNormalizationService.normalize("http://www.foo.com/index.htm") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/index.asp") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/index.aspx") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/index.php") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/index.php3") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/default.html") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/default.htm") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/default.asp") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/default.aspx") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/default.php") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/default.php3") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/something.php3") === "http://www.foo.com/something.php3"
      StaticNormalizationService.normalize("http://www.foo.com/something.html") === "http://www.foo.com/something.html"
      StaticNormalizationService.normalize("http://www.foo.com/something.asp") === "http://www.foo.com/something.asp"
      StaticNormalizationService.normalize("http://www.foo.com/index.phtml") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/index.cfm") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/index.cgi") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/index.HTML") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/index.Htm") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/index.ASP") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/index.jsp") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/index.jsf") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/index.jspx") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/index.jspfx") === "http://www.foo.com/index.jspfx"
      StaticNormalizationService.normalize("http://www.foo.com/index.jspa") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/index.jsps") === "http://www.foo.com/index.jsps"
      StaticNormalizationService.normalize("http://www.foo.com/index.aspX") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/index.PhP") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/index.PhP4") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/default.HTml") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/default.HTm") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/default.ASp") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/default.AspX") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/default.PHP") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/default.PHP3") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/index.phtml") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/index.cfm") === "http://www.foo.com"
      StaticNormalizationService.normalize("http://www.foo.com/index.cgi") === "http://www.foo.com"
    }

    "normalize path" in {
      StaticNormalizationService.normalize("http://keepitfindit.com/") === "http://keepitfindit.com"
      StaticNormalizationService.normalize("http://keepitfindit.com/?") === "http://keepitfindit.com"
      StaticNormalizationService.normalize("http://keepitfindit.com/?&") === "http://keepitfindit.com"
      StaticNormalizationService.normalize("http://keepitfindit.com/a?") === "http://keepitfindit.com/a"
      StaticNormalizationService.normalize("http://keepitfindit.com/a?&") === "http://keepitfindit.com/a"
      StaticNormalizationService.normalize("http://www.example.com/%7Eusername/") === "http://www.example.com/~username/"
      StaticNormalizationService.normalize("http://ACME.com/./foo") === "http://acme.com/foo"
      StaticNormalizationService.normalize("http://ACME.com/foo%26bar") === "http://acme.com/foo&bar"
      StaticNormalizationService.normalize("http://ACME.com/foo%20bar") === "http://acme.com/foo%20bar"
      StaticNormalizationService.normalize("http://www.example.com/../../a.html") === "http://www.example.com/a.html"
      StaticNormalizationService.normalize("http://www.example.com/../a/b/../c/./d.html") === "http://www.example.com/a/c/d.html"
      StaticNormalizationService.normalize("http://foo.bar.com?baz=1") === "http://foo.bar.com/?baz=1"
      StaticNormalizationService.normalize("http://somedomain.com/uploads/1/0/2/5/10259653/6199347.jpg?1325154037") ===
        "http://somedomain.com/uploads/1/0/2/5/10259653/6199347.jpg?1325154037"
    }

    "normalize query" in {
      StaticNormalizationService.normalize("http://keepitfindit.com/?a&") === "http://keepitfindit.com/?a"
      StaticNormalizationService.normalize("http://keepitfindit.com/?a&b") === "http://keepitfindit.com/?a&b"
      StaticNormalizationService.normalize("http://keepitfindit.com/?b&a") === "http://keepitfindit.com/?a&b"
      StaticNormalizationService.normalize("http://keepitfindit.com/p?a&b&") === "http://keepitfindit.com/p?a&b"
      StaticNormalizationService.normalize("http://keepitfindit.com/p?b=2&a=") === "http://keepitfindit.com/p?a=&b=2"
      StaticNormalizationService.normalize("http://keepitfindit.com/p?b=2&a=1&") === "http://keepitfindit.com/p?a=1&b=2"
      StaticNormalizationService.normalize("http://keepitfindit.com/p?b=2&c&a") === "http://keepitfindit.com/p?a&b=2&c"
      StaticNormalizationService.normalize("http://keepitfindit.com/p?a=1&c=3&b=2&a=0") === "http://keepitfindit.com/p?a=0&b=2&c=3"
      StaticNormalizationService.normalize("http://keepitfindit.com/p?a=1=1") === "http://keepitfindit.com/p?a=1%3D1"
      val escapedEqual = java.net.URLEncoder.encode("=", "UTF-8")
      StaticNormalizationService.normalize("http://keepitfindit.com/p?a=1"+escapedEqual+"1") === "http://keepitfindit.com/p?a=1%3D1"
      StaticNormalizationService.normalize("http://keepitfindit.com?foo=1") === "http://keepitfindit.com/?foo=1"
      StaticNormalizationService.normalize("http://keepitfindit.com?&foo=1") === "http://keepitfindit.com/?foo=1"

      StaticNormalizationService.normalize("http://www.example.com/?q=a+b") === "http://www.example.com/?q=a+b"
      StaticNormalizationService.normalize("http://www.example.com/display?category=foo/bar+baz") === "http://www.example.com/display?category=foo%2Fbar+baz"
      StaticNormalizationService.normalize("http://www.example.com/display?category=foo%2Fbar%20baz") === "http://www.example.com/display?category=foo%2Fbar+baz"
      StaticNormalizationService.normalize("http://www.example.com/p?q=a b") === "http://www.example.com/p?q=a+b"

      StaticNormalizationService.normalize("http://www.example.com/search?width=100%&height=100%") === "http://www.example.com/search?height=100%25&width=100%25"
      StaticNormalizationService.normalize("http://www.example.com/search?zoom=50%x50%") === "http://www.example.com/search?zoom=50%25x50%25"
    }

    "remove session parameters and tracking parameters" in {
      StaticNormalizationService.normalize("http://keepitfindit.com/p?a=1&jsessionid=1234") === "http://keepitfindit.com/p?a=1"
      StaticNormalizationService.normalize("http://keepitfindit.com/p?jsessionid=1234&a=1") === "http://keepitfindit.com/p?a=1"
      StaticNormalizationService.normalize("http://keepitfindit.com/p?jsessionid=1234&utm_source=5678") === "http://keepitfindit.com/p"
    }

    "handle edge cases" in {
      StaticNormalizationService.normalize("http://www1.bloomingdales.com/search/results.ognc?sortOption=*&Keyword=juicy%20couture&resultsPerPage=24&Action=sd&attrs=Department%3ADepartment%3ADresses|Color:Color:Black") ===
        "http://www1.bloomingdales.com/search/results.ognc?Action=sd&Keyword=juicy+couture&attrs=Department%3ADepartment%3ADresses%7CColor%3AColor%3ABlack&resultsPerPage=24&sortOption=*"

      StaticNormalizationService.normalize("http:///") === "http:///"
    }

    "use custom normalizer when applicable" in {
      // gmail
      StaticNormalizationService.normalize("https://mail.google.com/mail/ca/u/0/#inbox/13ae709b43798f58") ===
        "https://mail.google.com/mail/ca/u/0/#search//13ae709b43798f58"
      StaticNormalizationService.normalize("https://mail.google.com/mail/u/0/#imp/13bb4dcd0016031f") ===
        "https://mail.google.com/mail/u/0/#search//13bb4dcd0016031f"
      StaticNormalizationService.normalize("https://mail.google.com/mail/u/0/#sent") ===
        "https://mail.google.com/mail/u/0/#sent"
      // google drive
      StaticNormalizationService.normalize("https://docs.google.com/a/42go.com/document/d/1hrI0OWyPpe34NTMbkOq939nvF_4UwfWtc8b1LxV-mjk/edit") ===
        "https://docs.google.com/document/d/1hrI0OWyPpe34NTMbkOq939nvF_4UwfWtc8b1LxV-mjk/edit"

      // google docs
      StaticNormalizationService.normalize("https://docs.google.com/document/d/1pFRKQtcZFqBYRdfcRbYT3TQaZaFqI1PgeOHEacF57q8/edit") ===
        "https://docs.google.com/document/d/1pFRKQtcZFqBYRdfcRbYT3TQaZaFqI1PgeOHEacF57q8/edit"
      StaticNormalizationService.normalize("https://docs.google.com/a/fuks.co.il/document/d/1Va2VsQwZqIgB73Eb0cWqPSF-bClwEBVCdgE3Nyik0sI/edit") ===
        "https://docs.google.com/document/d/1Va2VsQwZqIgB73Eb0cWqPSF-bClwEBVCdgE3Nyik0sI/edit"
      StaticNormalizationService.normalize("https://docs.google.com/a/42go.com/file/d/0B17Ux0DwquOwOVNWRGJLcEt6SW8/edit") === "https://docs.google.com/file/d/0B17Ux0DwquOwOVNWRGJLcEt6SW8/edit"
      StaticNormalizationService.normalize("https://docs.google.com/spreadsheet/ccc?authkey=CKqfiacO&hl=en_US&key=0AmrEm2VP6NfgdGU5czBIdnBYUHBYSE9wRzd6Q3VvakE&ndplr=1") === "https://docs.google.com/spreadsheet/ccc?authkey=CKqfiacO&key=0AmrEm2VP6NfgdGU5czBIdnBYUHBYSE9wRzd6Q3VvakE"

      // youtube
      StaticNormalizationService.normalize("http://www.youtube.com/watch?NR=1&feature=endscreen&v=ill6RQDN5zI") === "http://www.youtube.com/watch?v=ill6RQDN5zI"
      StaticNormalizationService.normalize("https://www.youtube.com/watch?feature=player_embedded&v=DHEOF_rcND8") === "https://www.youtube.com/watch?v=DHEOF_rcND8"

      // techcrunch
      StaticNormalizationService.normalize("http://www.techcrunch.com") === "http://techcrunch.com"
      StaticNormalizationService.normalize("http://techcrunch.com") === "http://techcrunch.com"

      // amazon
      // - product
      StaticNormalizationService.normalize("http://www.amazon.com/Play-Framework-Cookbook-Alexander-Reelsen/dp/1849515522/ref=sr_1_1?ie=UTF8&qid=1355167842&sr=8-1&keywords=play+scala") ===
        "http://www.amazon.com/dp/1849515522"
      StaticNormalizationService.normalize("http://www.amazon.com/dp/1849515522/ref=sr_1_1?ie=UTF8&qid=1355167842&sr=8-1&keywords=play+scala") ===
        "http://www.amazon.com/dp/1849515522"
      StaticNormalizationService.normalize("http://www.amazon.com/gp/product/1849515522/ref=sr_1_1?ie=UTF8&qid=1355167842&sr=8-1&keywords=play+scala") ===
        "http://www.amazon.com/dp/1849515522"
        // - product reviews
      StaticNormalizationService.normalize("http://www.amazon.com/Play-Framework-Cookbook-Alexander-Reelsen/product-reviews/1849515522/ref=cm_cr_dp_synop?ie=UTF8&showViewpoints=0&sortBy=bySubmissionDateDescending#R1JYRF9OJ74H7G") ===
        "http://www.amazon.com/product-reviews/1849515522"
      StaticNormalizationService.normalize("http://www.amazon.com/product-reviews/1849515522/ref=cm_cr_dp_synop?ie=UTF8&showViewpoints=0&sortBy=bySubmissionDateDescending#R1JYRF9OJ74H7G") ===
        "http://www.amazon.com/product-reviews/1849515522"
      // - profiles
      StaticNormalizationService.normalize("http://www.amazon.com/gp/pdp/profile/A1BH4328F3ORDQ/ref=cm_cr_pr_pdp") ===
        "http://www.amazon.com/gp/pdp/profile/A1BH4328F3ORDQ"
      // - member reviews
      StaticNormalizationService.normalize("http://www.amazon.com/gp/cdp/member-reviews/A1BH4328F3ORDQ/ref=cm_pdp_rev_more?ie=UTF8&sort_by=MostRecentReview#RXT6FJYTTK625") ===
        "http://www.amazon.com/gp/cdp/member-reviews/A1BH4328F3ORDQ"
      // - wish list
      StaticNormalizationService.normalize("http://www.amazon.com/registry/wishlist/2VMW59G8OKZFM/ref=cm_pdp_wish_all_itms") ===
        "http://www.amazon.com/wishlist/2VMW59G8OKZFM"

      // LinkedIn
      // -profile
      var path = "http://www.linkedin.com/profile/view?goback=.npv_7564203_*1_*1_name_A1Fh_*1_en*4US_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1" +
      		".fps_PBCK_*1_*1_*1_*1_*1_*1_*1_2759913_*1_Y_*1_*1_*1_false_1_R_*1_*51_*1_*51_true_*1_*2_*2_*2_*2_*2_2759913_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2.npv_211337229" +
      		"_*1_*1_OUT*4OF*4NETWORK_2n*5d_*1_en*4US_*1_*1_*1_8ae3c0d1*59183*5411e*5a560*5a95d61f20602*50_3_7_ps_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1&id=12345678"
      StaticNormalizationService.normalize(path) === "http://www.linkedin.com/profile/view?id=12345678"

      path = "http://www.linkedin.com/profile/view?authToken=xXmj&authType=NAME_SEARCH&goback=.fps_PBCK_*1_John_Smith_*1_*1_*1_*1_*2_*1_Y_*1_*1_*1_false_1_R_*1_*51_*1_*51_true_" +
      		"*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2&id=12345678&locale=en_US&pvs=ps&srchid=717d2924-e9c4-40b7-8352-be3d67e2cddf-0" +
      		"&srchindex=1&srchtotal=3&trk=pp_profile_name_link"

      StaticNormalizationService.normalize(path) === "http://www.linkedin.com/profile/view?id=12345678"

      path = "http://www.linkedin.com/profile/view?id=12345678&authType=name&authToken=IsVM&trk=prof-connections-name"
      StaticNormalizationService.normalize(path) === "http://www.linkedin.com/profile/view?id=12345678"

    }
  }
}
