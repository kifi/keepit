package com.keepit.scraper.extractor

import org.specs2.mutable._
import org.apache.tika.sax.WriteOutContentHandler

class URITokenizerTest extends Specification {
  "URITokenizerTest" should {
    "extract keywords from URI" in {
      // extracts keywords from th epath part only for now
      URITokenizer.getTokens("http://kifi.com/documents/User_Guide.pdf") === Seq("documents", "User", "Guide", "pdf")
      URITokenizer.getTokens("http://kifi.com/documents/User_Guide") === Seq("documents", "User", "Guide")
      URITokenizer.getTokens("http://kifi.com/documents/User_Guide/") === Seq("documents", "User", "Guide")
      URITokenizer.getTokens("http://code.google.com/p/language-detection/") === Seq("p", "language", "detection")
    }
  }
}

