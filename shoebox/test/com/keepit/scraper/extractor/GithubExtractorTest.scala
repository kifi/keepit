package com.keepit.scraper.extractor

import org.specs2.mutable._
import org.jsoup.Jsoup
import com.keepit.common.net.URI
import java.io.FileInputStream
import com.keepit.scraper.HttpInputStream

class GithubExtractorTest extends Specification {

  def setup(url: String, file: String): String = {
    val uri = URI.parse(url).get
    val stream = new FileInputStream("test/com/keepit/scraper/extractor/" + file)
    val extractor = GithubExtractorFactory(uri)
    extractor.process(new HttpInputStream(stream))

    extractor.getContent()
  }

  "GithubExtractor" should {
    "parse an issue" in {
      val scraped = setup("https://github.com/kangax/fabric.js/issues/380", "githubcom_issue.txt")

      scraped.contains("fabric.js") === true
      scraped.contains("object x is partially or completely surrounded ") === true
      scraped.contains("andrewconner") === false
    }
    "parse the issues list" in {
      val scraped = setup("https://github.com/kangax/fabric.js/issues", "githubcom_issues.txt")

      scraped.contains("Bugfix for controlsAboveOverlay") === true
      scraped.contains("Support for stroke-dasharray feature") === true
      scraped.contains("andrewconner") === false
    }
    "parse a wiki page" in {
      val scraped = setup("https://github.com/kangax/fabric.js/wiki", "githubcom_wiki.txt")

      scraped.contains("Welcome to the fabric.js wiki") === true
      scraped.contains("How fabric canvas layering works") === true
      scraped.contains("andrewconner") === false
    }
    "parse a pull request" in {
      val scraped = setup("https://github.com/kangax/fabric.js/pull/381", "githubcom_pullrequest.txt")

      println(scraped)
      scraped.contains("Kienz opened this pull request") === true
      scraped.contains("Only if pointer is over targetCorner") === true
      scraped.contains("andrewconner") === false
    }
    "parse pull request list" in {
      val scraped = setup("https://github.com/kangax/fabric.js/pulls", "githubcom_pulls.txt")

      scraped.contains("Bugfix for controlsAboveOverlay (issue #380)") === true
      scraped.contains("It seemed to me that images") === true
      scraped.contains("andrewconner") === false
    }
    "parse profiles" in {
      val scraped = setup("https://github.com/andrewconner", "githubcom_profile.txt")

      (scraped.size > 100) === true
      scraped.contains("Andrew Conner") === true
      scraped.contains("All rights reserved") === false
    }
    "parse source page" in {
      val scraped = setup("https://github.com/kangax/fabric.js/blob/master/HEADER.js", "githubcom_source.txt")

      scraped.contains("True when in environment that supports touch events") === true
      scraped.contains("fabric.document = document") === true
      scraped.contains("andrewconner") === false
    }
    "parse source page" in {
      val scraped = setup("https://gist.github.com/4499206", "githubcom_gist.txt")

      scraped.contains("Proof-of-Concept exploit for Rails Remote Code Execution") === true
      scraped.contains("escaped_payload = escape_payload(wrap_payload(payload),target") === true
      scraped.contains("andrewconner") === false
    }
    "parse repo main page" in {
      val scraped = setup("https://gist.github.com/4499206", "githubcom_repo.txt")

      scraped.contains("Object Model for HTML5 Canvas + SVG-to-Canvas") === true
      scraped.contains("fabric.js") === true
      scraped.contains("No browser sniffing for critical functionality") === true
      scraped.contains("andrewconner") === false
    }
  }
}


