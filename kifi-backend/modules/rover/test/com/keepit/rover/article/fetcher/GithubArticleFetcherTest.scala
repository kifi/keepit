package com.keepit.scraper.extractor

import java.util.concurrent.TimeUnit

import com.keepit.rover.article.{ GithubArticle, DefaultArticle }
import com.keepit.rover.article.fetcher._
import com.keepit.rover.fetcher.{ FetchResult, FetchContext, HttpInputStream }
import com.keepit.rover.test.RoverTestInjector
import org.specs2.mutable._
import java.io.FileInputStream
import com.keepit.common.net.URI

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class GithubArticleFetcherTest extends Specification with RoverTestInjector {

  def mkRequest(file: String): ArticleFetchRequest[GithubArticle] = {
    val fileLocation = s"test/com/keepit/rover/article/fetcher/fixtures/$file"
    ArticleFetchRequest(GithubArticle, fileLocation)
  }

  def fetch(file: String)(implicit articleFetcher: ArticleFetcher[GithubArticle]): GithubArticle =
    Await.result(articleFetcher.fetch(mkRequest(file)).map(_.get), Duration(1, TimeUnit.SECONDS))

  withInjector(FileHttpFetcherModule()) { implicit injector =>

    implicit val articleFetcher = inject[GithubArticleFetcher]

    "GithubArticleFetcher" should {

      "parse an issue" in {
        val scraped = fetch("githubcom_issue.txt")
        val content = scraped.content.content.get

        content.contains("fabric.js") === true
        content.contains("object x is partially or completely surrounded ") === true
        content.contains("andrewconner") === false
      }

      "parse the issues list" in {
        val scraped = fetch("githubcom_issues.txt")
        val content = scraped.content.content.get

        content.contains("Bugfix for controlsAboveOverlay") === true
        content.contains("Support for stroke-dasharray feature") === true
        content.contains("andrewconner") === false
      }

      "parse a wiki page" in {
        val scraped = fetch("githubcom_wiki.txt")
        val content = scraped.content.content.get

        content.contains("Welcome to the fabric.js wiki") === true
        content.contains("How fabric canvas layering works") === true
        content.contains("andrewconner") === false
      }

      "parse a pull request" in {
        val scraped = fetch("githubcom_pullrequest.txt")
        val content = scraped.content.content.get

        content.contains("Kienz opened this pull request") === true
        content.contains("Only if pointer is over targetCorner") === true
        content.contains("andrewconner") === false
      }

      "parse pull request list" in {
        val scraped = fetch("githubcom_pulls.txt")
        val content = scraped.content.content.get

        content.contains("Bugfix for controlsAboveOverlay (issue #380)") === true
        content.contains("It seemed to me that images") === true
        content.contains("andrewconner") === false
      }

      "parse profiles" in {
        val scraped = fetch("githubcom_profile.txt")
        val content = scraped.content.content.get

        (content.size > 100) === true
        content.contains("Andrew Conner") === true
        content.contains("All rights reserved") === false
      }

      "parse source page" in {
        val scraped = fetch("githubcom_source.txt")
        val content = scraped.content.content.get

        content.contains("True when in environment that supports touch events") === true
        content.contains("fabric.document = document") === true
        content.contains("andrewconner") === false
      }

      "parse source page" in {
        val scraped = fetch("githubcom_gist.txt")
        val content = scraped.content.content.get

        content.contains("Proof-of-Concept exploit for Rails Remote Code Execution") === true
        content.contains("escaped_payload = escape_payload(wrap_payload(payload),target") === true
        content.contains("andrewconner") === false
      }

      "parse repo main page" in {
        val scraped = fetch("githubcom_repo.txt")
        val content = scraped.content.content.get

        content.contains("Object Model for HTML5 Canvas + SVG-to-Canvas") === true
        content.contains("fabric.js") === true
        content.contains("No browser sniffing for critical functionality") === true
        content.contains("andrewconner") === false
      }

    }

  }
}
