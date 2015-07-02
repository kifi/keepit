package com.keepit.normalizer

import org.specs2.mutable.Specification

class NormalizationCandidateSanitizerTest extends Specification {

  "NormalizationCandidateSanitize" should {

    "ignore URLs that are the page URL HTML-escaped twice" in {
      val url = "https://soundcloud.com/search?q=%D8%AF%DB%8C%D8%A7%D9%84%D9%88%DA%AF"
      val candidateUrl = "https://soundcloud.com/search?q=%25D8%25AF%25DB%258C%25D8%25A7%25D9%2584%25D9%2588%25DA%25AF"
      NormalizationCandidateSanitizer.validateCandidateUrl(url, candidateUrl) === None
    }

    "ignore URLs that are the page URL with parameter values URL-encoded twice" in {
      val url = "http://www.livejournal.com/gsearch?engine=google&cx=partner-pub-5600223439108080%3A3711723852&cof=FORID%3A10&ie=UTF-8&q=test&sa=Search&siteurl="
      val candidateUrl = "http://www.livejournal.com/gsearch?engine=google&amp;cx=partner-pub-5600223439108080%3A3711723852&amp;cof=FORID%3A10&amp;ie=UTF-8&amp;q=test&amp;sa=Search&amp;siteurl="
      NormalizationCandidateSanitizer.validateCandidateUrl(url, candidateUrl) === None
    }

  }

}
