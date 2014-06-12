package com.keepit.normalizer

import org.specs2.mutable.Specification

class PrenormalizerTest extends Specification {

  def prenormalize(url: String) = Prenormalizer(url).get

  "Prenormalizer" should {

    "upcase scheme and host" in {
      prenormalize("HTTP://KeepItFindIt.com") === "http://keepitfindit.com"
    }

    "remove port 80" in {
      prenormalize("HTTP://KeepItFindIt.com:8080") === "http://keepitfindit.com:8080"
      prenormalize("HTTP://KeepItFindIt.com:80") === "http://keepitfindit.com"
    }

    "remove fragment" in {
      prenormalize("http://keepitfindit.com/path#abc") === "http://keepitfindit.com/path"
      prenormalize("http://keepitfindit.com/page?xyz=123#abc") === "http://keepitfindit.com/page?xyz=123"
      //prenormalize("http://www.foo.com/foo.html#something?x=y") === "http://www.foo.com/foo.html?x=y"
    }

    "remove default pages (index.html, etc.)" in {
      prenormalize("http://www.example.com/index.html") === "http://www.example.com"
      prenormalize("http://www.example.com/A/index.html") === "http://www.example.com/A/"
      prenormalize("http://www.example.com/A/B/index.html") === "http://www.example.com/A/B/"
      prenormalize("http://keepitfindit.com/index.html#abc") === "http://keepitfindit.com"
      prenormalize("http://keepitfindit.com/index.html?a=b") === "http://keepitfindit.com/?a=b"

      // taken from https://svn.apache.org/repos/asf/nutch/trunk/src/plugin/urlnormalizer-regex/sample/regex-normalize-default.test
      // and modified
      prenormalize("http://www.foo.com/index.htm") === "http://www.foo.com"
      prenormalize("http://www.foo.com/index.asp") === "http://www.foo.com"
      prenormalize("http://www.foo.com/index.aspx") === "http://www.foo.com"
      prenormalize("http://www.foo.com/index.php") === "http://www.foo.com"
      prenormalize("http://www.foo.com/index.php3") === "http://www.foo.com"
      prenormalize("http://www.foo.com/default.html") === "http://www.foo.com"
      prenormalize("http://www.foo.com/default.htm") === "http://www.foo.com"
      prenormalize("http://www.foo.com/default.asp") === "http://www.foo.com"
      prenormalize("http://www.foo.com/default.aspx") === "http://www.foo.com"
      prenormalize("http://www.foo.com/default.php") === "http://www.foo.com"
      prenormalize("http://www.foo.com/default.php3") === "http://www.foo.com"
      prenormalize("http://www.foo.com/something.php3") === "http://www.foo.com/something.php3"
      prenormalize("http://www.foo.com/something.html") === "http://www.foo.com/something.html"
      prenormalize("http://www.foo.com/something.asp") === "http://www.foo.com/something.asp"
      prenormalize("http://www.foo.com/index.phtml") === "http://www.foo.com"
      prenormalize("http://www.foo.com/index.cfm") === "http://www.foo.com"
      prenormalize("http://www.foo.com/index.cgi") === "http://www.foo.com"
      prenormalize("http://www.foo.com/index.HTML") === "http://www.foo.com"
      prenormalize("http://www.foo.com/index.Htm") === "http://www.foo.com"
      prenormalize("http://www.foo.com/index.ASP") === "http://www.foo.com"
      prenormalize("http://www.foo.com/index.jsp") === "http://www.foo.com"
      prenormalize("http://www.foo.com/index.jsf") === "http://www.foo.com"
      prenormalize("http://www.foo.com/index.jspx") === "http://www.foo.com"
      prenormalize("http://www.foo.com/index.jspfx") === "http://www.foo.com/index.jspfx"
      prenormalize("http://www.foo.com/index.jspa") === "http://www.foo.com"
      prenormalize("http://www.foo.com/index.jsps") === "http://www.foo.com/index.jsps"
      prenormalize("http://www.foo.com/index.aspX") === "http://www.foo.com"
      prenormalize("http://www.foo.com/index.PhP") === "http://www.foo.com"
      prenormalize("http://www.foo.com/index.PhP4") === "http://www.foo.com"
      prenormalize("http://www.foo.com/default.HTml") === "http://www.foo.com"
      prenormalize("http://www.foo.com/default.HTm") === "http://www.foo.com"
      prenormalize("http://www.foo.com/default.ASp") === "http://www.foo.com"
      prenormalize("http://www.foo.com/default.AspX") === "http://www.foo.com"
      prenormalize("http://www.foo.com/default.PHP") === "http://www.foo.com"
      prenormalize("http://www.foo.com/default.PHP3") === "http://www.foo.com"
      prenormalize("http://www.foo.com/index.phtml") === "http://www.foo.com"
      prenormalize("http://www.foo.com/index.cfm") === "http://www.foo.com"
      prenormalize("http://www.foo.com/index.cgi") === "http://www.foo.com"
    }

    "normalize path" in {
      prenormalize("http://keepitfindit.com/") === "http://keepitfindit.com"
      prenormalize("http://keepitfindit.com/?") === "http://keepitfindit.com"
      prenormalize("http://keepitfindit.com/?&") === "http://keepitfindit.com"
      prenormalize("http://keepitfindit.com/a?") === "http://keepitfindit.com/a"
      prenormalize("http://keepitfindit.com/a?&") === "http://keepitfindit.com/a"
      prenormalize("http://www.example.com/%7Eusername/") === "http://www.example.com/~username/"
      prenormalize("http://ACME.com/./foo") === "http://acme.com/foo"
      prenormalize("http://ACME.com/foo%26bar") === "http://acme.com/foo&bar"
      prenormalize("http://ACME.com/foo%20bar") === "http://acme.com/foo%20bar"
      prenormalize("http://www.example.com/../../a.html") === "http://www.example.com/a.html"
      prenormalize("http://www.example.com/../a/b/../c/./d.html") === "http://www.example.com/a/c/d.html"
      prenormalize("http://foo.bar.com?baz=1") === "http://foo.bar.com/?baz=1"
      prenormalize("http://somedomain.com/uploads/1/0/2/5/10259653/6199347.jpg?1325154037") ===
        "http://somedomain.com/uploads/1/0/2/5/10259653/6199347.jpg?1325154037"
    }

    "normalize query" in {
      prenormalize("http://keepitfindit.com/?a&") === "http://keepitfindit.com/?a"
      prenormalize("http://keepitfindit.com/?a&b") === "http://keepitfindit.com/?a&b"
      prenormalize("http://keepitfindit.com/?b&a") === "http://keepitfindit.com/?a&b"
      prenormalize("http://keepitfindit.com/p?a&b&") === "http://keepitfindit.com/p?a&b"
      prenormalize("http://keepitfindit.com/p?b=2&a=") === "http://keepitfindit.com/p?a=&b=2"
      prenormalize("http://keepitfindit.com/p?b=2&a=1&") === "http://keepitfindit.com/p?a=1&b=2"
      prenormalize("http://keepitfindit.com/p?b=2&c&a") === "http://keepitfindit.com/p?a&b=2&c"
      prenormalize("http://keepitfindit.com/p?a=1&c=3&b=2&a=0") === "http://keepitfindit.com/p?a=0&b=2&c=3"
      prenormalize("http://keepitfindit.com/p?a=1=1") === "http://keepitfindit.com/p?a=1=1"
      val escapedEqual = java.net.URLEncoder.encode("=", "UTF-8")
      prenormalize("http://keepitfindit.com/p?a=1"+escapedEqual+"1") === "http://keepitfindit.com/p?a=1=1"
      prenormalize("http://keepitfindit.com?foo=1") === "http://keepitfindit.com/?foo=1"
      prenormalize("http://keepitfindit.com?&foo=1") === "http://keepitfindit.com/?foo=1"

      prenormalize("http://www.example.com/?q=a+b") === "http://www.example.com/?q=a+b"
      prenormalize("http://www.example.com/display?category=foo/bar+baz") === "http://www.example.com/display?category=foo/bar+baz"
      prenormalize("http://www.example.com/display?category=foo%2Fbar%20baz") === "http://www.example.com/display?category=foo/bar+baz"
      prenormalize("http://www.example.com/p?q=a b") === "http://www.example.com/p?q=a+b"

      prenormalize("http://www.example.com/search?width=100%&height=100%") === "http://www.example.com/search?height=100%25&width=100%25"
      prenormalize("http://www.example.com/search?zoom=50%x50%") === "http://www.example.com/search?zoom=50%25x50%25"
    }

    "remove session parameters and tracking parameters" in {
      prenormalize("http://keepitfindit.com/p?a=1&jsessionid=1234") === "http://keepitfindit.com/p?a=1"
      prenormalize("http://keepitfindit.com/p?jsessionid=1234&a=1") === "http://keepitfindit.com/p?a=1"
      prenormalize("http://keepitfindit.com/p?jsessionid=1234&utm_source=5678") === "http://keepitfindit.com/p"
    }

    "handle edge cases" in {
      prenormalize("http://www1.bloomingdales.com/search/results.ognc?sortOption=*&Keyword=juicy%20couture&resultsPerPage=24&Action=sd&attrs=Department%3ADepartment%3ADresses|Color:Color:Black") ===
        "http://www1.bloomingdales.com/search/results.ognc?Action=sd&Keyword=juicy+couture&attrs=Department:Department:Dresses%7CColor:Color:Black&resultsPerPage=24&sortOption=*"

      // dots after the domain name
      prenormalize("http://www.42go.com./team.html") === "http://www.42go.com./team.html"
      prenormalize("http://www.42go.com..../team.html") === "http://www.42go.com..../team.html"
    }

    "use custom normalizer when applicable" in {
      // gmail
      prenormalize("https://mail.google.com/mail/ca/u/0/#inbox/13ae709b43798f58") ===
        "https://mail.google.com/mail/ca/u/0/#search//13ae709b43798f58"
      prenormalize("https://mail.google.com/mail/u/0/#imp/13bb4dcd0016031f") ===
        "https://mail.google.com/mail/u/0/#search//13bb4dcd0016031f"
      prenormalize("https://mail.google.com/mail/u/0/#sent") ===
        "https://mail.google.com/mail/u/0/#sent"
      // google drive
      prenormalize("https://docs.google.com/a/42go.com/document/d/1hrI0OWyPpe34NTMbkOq939nvF_4UwfWtc8b1LxV-mjk/edit") ===
        "https://docs.google.com/document/d/1hrI0OWyPpe34NTMbkOq939nvF_4UwfWtc8b1LxV-mjk/edit"
      prenormalize("https://drive.google.com/a/42go.com/#folders/0B_SswQqUaqw6c1dteUNRUkdLRGs") ===
        "https://drive.google.com#folders/0B_SswQqUaqw6c1dteUNRUkdLRGs"
      prenormalize("https://drive.google.com/a/42go.com/#search/spec") ===
        "https://drive.google.com#search/spec"
      prenormalize("https://drive.google.com/a/42go.com/#query?view=2&filter=images") ===
        "https://drive.google.com#query?view=2&filter=images"
      prenormalize("https://drive.google.com/a/42go.com/#my-drive") ===
        "https://drive.google.com#my-drive"
      prenormalize("https://drive.google.com/a/42go.com/?tab=wo#shared-with-me") ===
        "https://drive.google.com#shared-with-me"

      // google docs
      prenormalize("https://docs.google.com/document/d/1pFRKQtcZFqBYRdfcRbYT3TQaZaFqI1PgeOHEacF57q8/edit") ===
        "https://docs.google.com/document/d/1pFRKQtcZFqBYRdfcRbYT3TQaZaFqI1PgeOHEacF57q8/edit"
      prenormalize("https://docs.google.com/a/fuks.co.il/document/d/1Va2VsQwZqIgB73Eb0cWqPSF-bClwEBVCdgE3Nyik0sI/edit") ===
        "https://docs.google.com/document/d/1Va2VsQwZqIgB73Eb0cWqPSF-bClwEBVCdgE3Nyik0sI/edit"
      prenormalize("https://docs.google.com/a/42go.com/file/d/0B17Ux0DwquOwOVNWRGJLcEt6SW8/edit") === "https://docs.google.com/file/d/0B17Ux0DwquOwOVNWRGJLcEt6SW8/edit"
      prenormalize("https://docs.google.com/spreadsheet/ccc?authkey=CKqfiacO&hl=en_US&key=0AmrEm2VP6NfgdGU5czBIdnBYUHBYSE9wRzd6Q3VvakE&ndplr=1") === "https://docs.google.com/spreadsheet/ccc?authkey=CKqfiacO&key=0AmrEm2VP6NfgdGU5czBIdnBYUHBYSE9wRzd6Q3VvakE"

      // google search
      prenormalize("https://www.google.com/search?q=kifi&oq=kifi&aqs=chrome..69i57j69i60l3j69i65l2.3958j0j7&sourceid=chrome&espv=210&es_sm=91&ie=UTF-8&safe=active") ===
        "https://www.google.com/search?ie=UTF-8&q=kifi&safe=active"
      prenormalize("https://www.google.com/search?q=kifi&oq=kifi&aqs=chrome..69i57j69i60l3j69i65l2.3958j0j7&sourceid=chrome&espv=210&es_sm=91&ie=UTF-8&safe=active#q=find+engine") ===
        "https://www.google.com/search?ie=UTF-8&q=find+engine&safe=active"

      // youtube
      prenormalize("http://www.youtube.com/watch?NR=1&feature=endscreen&v=ill6RQDN5zI") === "http://www.youtube.com/watch?v=ill6RQDN5zI"
      prenormalize("https://www.youtube.com/watch?feature=player_embedded&v=DHEOF_rcND8") === "https://www.youtube.com/watch?v=DHEOF_rcND8"

      // amazon
      // - product
      prenormalize("http://www.amazon.com/Play-Framework-Cookbook-Alexander-Reelsen/dp/1849515522/ref=sr_1_1?ie=UTF8&qid=1355167842&sr=8-1&keywords=play+scala") ===
        "http://www.amazon.com/dp/1849515522"
      prenormalize("http://www.amazon.fr/Play-Framework-Cookbook-Alexander-Reelsen/dp/1849515522/ref=sr_1_1?ie=UTF8&qid=1355167842&sr=8-1&keywords=play+scala") ===
        "http://www.amazon.fr/dp/1849515522"
      prenormalize("http://www.amazon.co.jp/Play-Framework-Cookbook-Alexander-Reelsen/dp/1849515522/ref=sr_1_1?ie=UTF8&qid=1355167842&sr=8-1&keywords=play+scala") ===
        "http://www.amazon.co.jp/dp/1849515522"
      prenormalize("http://amazon.co.jp/Play-Framework-Cookbook-Alexander-Reelsen/dp/1849515522/ref=sr_1_1?ie=UTF8&qid=1355167842&sr=8-1&keywords=play+scala") ===
        "http://www.amazon.co.jp/dp/1849515522"
      prenormalize("http://www.amazon.com/dp/1849515522/ref=sr_1_1?ie=UTF8&qid=1355167842&sr=8-1&keywords=play+scala") ===
        "http://www.amazon.com/dp/1849515522"
      prenormalize("http://www.amazon.com/gp/aw/d/B00BHJRYYS/ref=br_mps_pdt-2/178-6590437-2407965?pf_rd_i=507846&pf_rd_m=ATVPDKIKX0DER&pf_rd_p=1665868822&pf_rd_r=00DADGHAS36CTKK5FM96&pf_rd_s=mobile-1&pf_rd_t=101") ===
        "http://www.amazon.com/dp/B00BHJRYYS"
      prenormalize("http://www.amazon.com/gp/product/1849515522/ref=sr_1_1?ie=UTF8&qid=1355167842&sr=8-1&keywords=play+scala") ===
        "http://www.amazon.com/dp/1849515522"
        // - product reviews
      prenormalize("http://www.amazon.com/Play-Framework-Cookbook-Alexander-Reelsen/product-reviews/1849515522/ref=cm_cr_dp_synop?ie=UTF8&showViewpoints=0&sortBy=bySubmissionDateDescending#R1JYRF9OJ74H7G") ===
        "http://www.amazon.com/product-reviews/1849515522"
      prenormalize("http://www.amazon.com/gp/aw/cr/B00FTR018U/ref=mw_dp_cr?qid=1394812947&sr=8-2") ===
        "http://www.amazon.com/product-reviews/B00FTR018U"
      prenormalize("http://www.amazon.com/product-reviews/1849515522/ref=cm_cr_dp_synop?ie=UTF8&showViewpoints=0&sortBy=bySubmissionDateDescending#R1JYRF9OJ74H7G") ===
        "http://www.amazon.com/product-reviews/1849515522"
      // - profiles
      prenormalize("http://www.amazon.com/gp/pdp/profile/A1BH4328F3ORDQ/ref=cm_cr_pr_pdp") ===
        "http://www.amazon.com/gp/pdp/profile/A1BH4328F3ORDQ"
      // - member reviews
      prenormalize("http://www.amazon.com/gp/cdp/member-reviews/A1BH4328F3ORDQ/ref=cm_pdp_rev_more?ie=UTF8&sort_by=MostRecentReview#RXT6FJYTTK625") ===
        "http://www.amazon.com/gp/cdp/member-reviews/A1BH4328F3ORDQ"
      // - wish list
      prenormalize("http://www.amazon.com/registry/wishlist/2VMW59G8OKZFM/ref=cm_pdp_wish_all_itms") ===
        "http://www.amazon.com/wishlist/2VMW59G8OKZFM"

      // LinkedIn
      // -profile
      var path = "http://www.linkedin.com/profile/view?goback=.npv_7564203_*1_*1_name_A1Fh_*1_en*4US_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1" +
          ".fps_PBCK_*1_*1_*1_*1_*1_*1_*1_2759913_*1_Y_*1_*1_*1_false_1_R_*1_*51_*1_*51_true_*1_*2_*2_*2_*2_*2_2759913_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2.npv_211337229" +
          "_*1_*1_OUT*4OF*4NETWORK_2n*5d_*1_en*4US_*1_*1_*1_8ae3c0d1*59183*5411e*5a560*5a95d61f20602*50_3_7_ps_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1_*1&id=12345678"
      prenormalize(path) === "http://www.linkedin.com/profile/view?id=12345678"

      path = "http://www.linkedin.com/profile/view?authToken=xXmj&authType=NAME_SEARCH&goback=.fps_PBCK_*1_John_Smith_*1_*1_*1_*1_*2_*1_Y_*1_*1_*1_false_1_R_*1_*51_*1_*51_true_" +
          "*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2_*2&locale=en_US&pvs=ps&srchid=717d2924-e9c4-40b7-8352-be3d67e2cddf-0&id=12345678" +
          "&srchindex=1&srchtotal=3&trk=pp_profile_name_link"

      prenormalize(path) === "http://www.linkedin.com/profile/view?id=12345678"

      path = "http://www.linkedin.com/profile/view?id=12345678&authType=name&authToken=IsVM&trk=prof-connections-name"
      prenormalize(path) === "http://www.linkedin.com/profile/view?id=12345678"

      path = "https://www.linkedin.com/in/leogrimaldi/"
      prenormalize(path) === "https://www.linkedin.com/in/leogrimaldi"

      path = "http://fr.linkedin.com/in/leogrimaldi/en"
      prenormalize(path) === "http://www.linkedin.com/in/leogrimaldi/en"

      path = "http://fr.linkedin.com/pub/vivien-saulue/39/706/b06"
      prenormalize(path) === "http://www.linkedin.com/pub/vivien-saulue/39/706/b06"

      path = "http://fr.linkedin.com/pub/marc-milowski/13/640/1a8/en"
      prenormalize(path) === "http://www.linkedin.com/pub/marc-milowski/13/640/1a8/en"

      path = "https://touch.www.linkedin.com/?as=false&rs=false&sessionid=1260325066244096#public-profile/https://www.linkedin.com/in/vanmendoza"
      prenormalize(path) === "https://www.linkedin.com/in/vanmendoza"

      path = "https://touch.www.linkedin.com/?as=false&can=https%253A%252F%252Fwww.linkedin.com%252Fin%252Fjackwchou&rs=false&sessionid=8282684388278272#public-profile/https://www.linkedin.com/in/jackwchou"
      prenormalize(path) === "https://www.linkedin.com/in/jackwchou"

      path = "https://touch.www.linkedin.com/mainsite?redirect_url=https://www.linkedin.com/today/post/article/20140204074411-659753-loners-can-win-at-school-they-can-t-in-the-real-world?sf22432825=1&_mSplash=1&rs=false&sessionid=5684900904566784"
      prenormalize(path) === "https://www.linkedin.com/today/post/article/20140204074411-659753-loners-can-win-at-school-they-can-t-in-the-real-world"

      path = "https://touch.www.linkedin.com/mainsite?redirect_url=https://www.linkedin.com/today/post/article/20140605083410-93094-don-t-kill-your-channels?_mSplash=1&rs=false&sessionid=2738350133870592"
      prenormalize(path) === "https://www.linkedin.com/today/post/article/20140605083410-93094-don-t-kill-your-channels"

      path = "https://touch.www.linkedin.com/?as=false&can=https%253A%252F%252Fwww.linkedin.com%252Fpub%252Fleslie-chang%252F1a%252F769%252Fb82&dl=no&rs=false&sessionid=4907903918014464#profile/67575122"
      prenormalize(path) === "https://www.linkedin.com/pub/leslie-chang/1a/769/b82"


      //Wikipedia

      prenormalize("http://en.m.wikipedia.org/wiki/Douze") === "https://en.wikipedia.org/wiki/Douze"
      prenormalize("http://en.wikipedia.org/wiki/Douze") === "https://en.wikipedia.org/wiki/Douze"
      prenormalize("https://en.wikipedia.org/wiki/Douze") === "https://en.wikipedia.org/wiki/Douze"
      prenormalize("http://fr.m.wikipedia.org/wiki/Douze_(rivière)") === "https://fr.wikipedia.org/wiki/Douze_(rivière)"
      prenormalize("http://fr.wikipedia.org/wiki/Douze_(rivière)") === "https://fr.wikipedia.org/wiki/Douze_(rivière)"

      //Quora
      prenormalize("http://www.quora.com") === "https://www.quora.com"
      prenormalize("http://blog.quora.com/Making-Sharing-Better") === "https://blog.quora.com/Making-Sharing-Better"
      path = "http://www.quora.com/Software-Engineering/What-makes-a-good-engineering-culture?ref=fb"
      prenormalize(path) === "https://www.quora.com/Software-Engineering/What-makes-a-good-engineering-culture?share=1"
    }
  }
}
