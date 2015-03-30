package com.keepit.rover.document.utils

import com.keepit.common.net.URI
import org.specs2.mutable._

class URITokenizerTest extends Specification {
  "URITokenizerTest" should {
    "extract keywords from URI" in {
      // extracts keywords from th epath part only for now
      URITokenizer.getTokens(URI.parse("http://kifi.com/documents/User_Guide.pdf").get) === Seq("documents", "User", "Guide", "pdf")
      URITokenizer.getTokens(URI.parse("http://kifi.com/documents/User_Guide").get) === Seq("documents", "User", "Guide")
      URITokenizer.getTokens(URI.parse("http://kifi.com/documents/User_Guide/").get) === Seq("documents", "User", "Guide")
      URITokenizer.getTokens(URI.parse("http://code.google.com/p/language-detection/").get) === Seq("p", "language", "detection")
    }
  }
}

