package com.keepit.normalizer

import org.specs2.mutable.Specification

class NormalizationCandidateSanitizerTest extends Specification {

  "NormalizationCandidateSanitize" should {

    "normalize url encodings" in {
      val url = "https://soundcloud.com/search"
      val candidateUrl = "https://soundcloud.com/search?q=%D8%AF%DB%8C%D8%A7%D9%84%D9%88%DA%AF"
      NormalizationCandidateSanitizer.validateCandidateUrl(url, candidateUrl) === Some("https://soundcloud.com/search?q=دیالوگ")
    }

  }

}
