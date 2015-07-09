package com.keepit.rover.article.fetcher

import com.keepit.rover.article.GithubArticle

class GithubArticleFetcherTest extends ArticleFetcherTest[GithubArticle, GithubArticleFetcher] {

  withInjector(FileHttpFetcherModule()) { implicit injector =>

    "GithubArticleFetcher" should {

      "parse an issue" in {
        val scraped = fetch("https://github.com/kangax/fabric.js/issues/380", "githubcom_issue.txt")
        val content = scraped.content.content.get

        content.contains("fabric.js") === true
        content.contains("object x is partially or completely surrounded ") === true
        content.contains("andrewconner") === false
      }

      "parse the issues list" in {
        val scraped = fetch("https://github.com/kangax/fabric.js/issues", "githubcom_issues.txt")
        val content = scraped.content.content.get

        content.contains("Bugfix for controlsAboveOverlay") === true
        content.contains("Support for stroke-dasharray feature") === true
        content.contains("andrewconner") === false
      }

      "parse a wiki page" in {
        val scraped = fetch("https://github.com/kangax/fabric.js/wiki", "githubcom_wiki.txt")
        val content = scraped.content.content.get

        content.contains("Welcome to the fabric.js wiki") === true
        content.contains("How fabric canvas layering works") === true
        content.contains("andrewconner") === false
      }

      "parse a pull request" in {
        val scraped = fetch("https://github.com/kangax/fabric.js/pull/381", "githubcom_pullrequest.txt")
        val content = scraped.content.content.get

        content.contains("Kienz opened this pull request") === true
        content.contains("Only if pointer is over targetCorner") === true
        content.contains("andrewconner") === false
      }

      "parse pull request list" in {
        val scraped = fetch("https://github.com/kangax/fabric.js/pulls", "githubcom_pulls.txt")
        val content = scraped.content.content.get

        content.contains("Bugfix for controlsAboveOverlay (issue #380)") === true
        content.contains("It seemed to me that images") === true
        content.contains("andrewconner") === false
      }

      "parse profiles" in {
        val scraped = fetch("https://github.com/andrewconner", "githubcom_profile.txt")
        val content = scraped.content.content.get

        (content.length > 100) === true
        content.contains("Andrew Conner") === true
        content.contains("All rights reserved") === false
      }

      "parse source page" in {
        val scraped = fetch("https://github.com/kangax/fabric.js/blob/master/HEADER.js", "githubcom_source.txt")
        val content = scraped.content.content.get

        content.contains("True when in environment that supports touch events") === true
        content.contains("fabric.document = document") === true
        content.contains("andrewconner") === false
      }

      "parse source page" in {
        val scraped = fetch("https://gist.github.com/4499206", "githubcom_gist.txt")
        val content = scraped.content.content.get

        content.contains("Proof-of-Concept exploit for Rails Remote Code Execution") === true
        content.contains("escaped_payload = escape_payload(wrap_payload(payload),target") === true
        content.contains("andrewconner") === false
      }

      "parse repo main page" in {
        val scraped = fetch("https://gist.github.com/4499206", "githubcom_repo.txt")
        val content = scraped.content.content.get

        content.contains("Object Model for HTML5 Canvas + SVG-to-Canvas") === true
        content.contains("fabric.js") === true
        content.contains("No browser sniffing for critical functionality") === true
        content.contains("andrewconner") === false
      }

    }

  }

}
