package com.keepit.rover.article.fetcher

import com.keepit.rover.article.LinkedInProfileArticle
import org.specs2.matcher.{ MatchResult, Expectable, Matcher }

class LinkedInProfileArticleFetcherTest extends ArticleFetcherTest[LinkedInProfileArticle, LinkedInProfileArticleFetcher] {

  def containKeywords(matches: String*) = matches.toSet.flatMap((s: String) => s.split(" ")).map(contain).reduce(_ and _)

  "LinkedInArticleProfileFetcher" should {

    "parse profile data correctly" in {

      withDb(FileHttpFetcherModule()) { implicit injector =>

        val scraped = fetch("https://www.linkedin.com/in/leogrim", "linkedin_profile.txt")
        val profile = scraped.content.profile

        profile.id === Some("17558679")
        profile.title must containKeywords("Grimaldi Software Engineer Kifi")
        profile.overview must containKeywords("San Francisco Bay Area Stanford")
        profile.sections must containKeywords("San Francisco Bay Area Scala Engineering Education Technicolor technical answer")
      }

    }
  }

}
