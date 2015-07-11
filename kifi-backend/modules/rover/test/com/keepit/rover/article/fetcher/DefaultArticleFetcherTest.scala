package com.keepit.rover.article.fetcher

import com.keepit.rover.article.{ ArticleKind, DefaultArticle }
import org.specs2.mutable.Specification

class DefaultArticleFetcherTest extends ArticleFetcherTest[DefaultArticle, DefaultArticleFetcher] {

  "DefaultArticleFetcher" should {

    "parse doc 1" in {
      withDb(FileHttpFetcherModule()) { implicit injector =>
        val scraped = fetch("https://cnn.com/url1", "money.cnn.com.dimon-pay.txt")
        val normalization = scraped.content.normalization
        normalization.canonicalUrl === Some("http://money.cnn.com/2014/01/24/news/companies/dimon-pay/index.html")
        normalization.alternateUrls === Set()
        normalization.openGraphUrl === Some("http://money.cnn.com/2014/01/24/news/companies/dimon-pay/index.html")
      }
    }

    "parse doc 2" in {
      withDb(FileHttpFetcherModule()) { implicit injector =>
        val scraped = fetch("https://cnn.com/url2", "www.cnn.com.health.txt")
        val normalization = scraped.content.normalization
        normalization.canonicalUrl === Some("http://www.cnn.com/video/data/2.0/video/us/2014/01/24/newday-live-larson-u-s-olympic-team-uniforms.cnn-ap.html")
        normalization.alternateUrls === Set()
        normalization.openGraphUrl === Some("http://www.cnn.com/video/data/2.0/video/us/2014/01/24/newday-live-larson-u-s-olympic-team-uniforms.cnn-ap.html")
      }
    }

    "parse doc 3" in {
      withDb(FileHttpFetcherModule()) { implicit injector =>
        val scraped = fetch("https://cnn.com/url3", "www.cnn.com.pregnant-brain-dead-woman-texas.txt")
        val normalization = scraped.content.normalization
        normalization.canonicalUrl === Some("http://www.cnn.com/2014/01/24/health/pregnant-brain-dead-woman-texas/index.html")
        normalization.alternateUrls === Set("http://rss.cnn.com/rss/cnn_health.rss")
        normalization.openGraphUrl === Some("http://www.cnn.com/2014/01/24/health/pregnant-brain-dead-woman-texas/index.html")
      }
    }

    "parse doc with strange alternates" in {
      withDb(FileHttpFetcherModule()) { implicit injector =>
        val scraped = fetch("http://www.secrefer.com/login/?next=/apply/company/11", "secrefer.com.txt")
        val normalization = scraped.content.normalization
        normalization.canonicalUrl === None
        normalization.alternateUrls === Set()
        normalization.openGraphUrl === Some("http://www.secrefer.com/login/?next=/apply/company/11?location=random?location=random")
      }
    }

    "parse doc with alternates" in {
      withDb(FileHttpFetcherModule()) { implicit injector =>
        val scraped = fetch("http://www.bbc.co.uk/news/technology-25233230", "cnn.uk.txt")
        val normalization = scraped.content.normalization
        normalization.canonicalUrl === Some("http://www.bbc.co.uk/news/technology-25233230")
        normalization.alternateUrls === Set("http://www.bbc.co.uk/news/technology-25233230", "http://www.bbc.com/news/technology-25233230")
        normalization.openGraphUrl === Some("http://www.bbc.co.uk/news/technology-25233230")
      }
    }

    "extract keywords" in {
      withDb(FileHttpFetcherModule()) { implicit injector =>
        val scraped = fetch("https://cnn.com/url2", "www.cnn.com.health.txt")
        val normalization = scraped.content.normalization
        normalization.canonicalUrl === Some("http://www.cnn.com/video/data/2.0/video/us/2014/01/24/newday-live-larson-u-s-olympic-team-uniforms.cnn-ap.html")
        normalization.openGraphUrl === Some("http://www.cnn.com/video/data/2.0/video/us/2014/01/24/newday-live-larson-u-s-olympic-team-uniforms.cnn-ap.html")

        val keywords = scraped.content.keywords
        keywords !== None
        keywords.exists(_.contains("Olympics")) === true
      }
    }
  }

}
