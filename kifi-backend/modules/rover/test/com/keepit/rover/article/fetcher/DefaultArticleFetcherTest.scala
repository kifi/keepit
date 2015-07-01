package com.keepit.rover.article.fetcher

import com.keepit.rover.article.{ ArticleKind, DefaultArticle }
import org.specs2.mutable.Specification

class DefaultArticleFetcherTest extends Specification with ArticleFetcherTest[DefaultArticle, DefaultArticleFetcher] {

  override val fetcherClass = classOf[DefaultArticleFetcher]
  override val articleKind = DefaultArticle

  "DefaultArticleFetcher" should {
    "parse doc 1" in {
      val scraped = fetch("money.cnn.com.dimon-pay.txt")
      val normalization = scraped.content.normalization
      normalization.canonicalUrl === Some("http://money.cnn.com/2014/01/24/news/companies/dimon-pay/index.html")
      normalization.alternateUrls === Set()
      normalization.openGraphUrl === Some("http://money.cnn.com/2014/01/24/news/companies/dimon-pay/index.html")
    }
    "parse doc 2" in {
      val scraped = fetch("www.cnn.com.health.txt")
      val normalization = scraped.content.normalization
      normalization.canonicalUrl === Some("http://www.cnn.com/video/data/2.0/video/us/2014/01/24/newday-live-larson-u-s-olympic-team-uniforms.cnn-ap.html")
      normalization.alternateUrls === Set()
      normalization.openGraphUrl === Some("http://www.cnn.com/video/data/2.0/video/us/2014/01/24/newday-live-larson-u-s-olympic-team-uniforms.cnn-ap.html")
    }

    "parse doc 3" in {
      val scraped = fetch("www.cnn.com.pregnant-brain-dead-woman-texas.txt")
      val normalization = scraped.content.normalization
      normalization.canonicalUrl === Some("http://www.cnn.com/2014/01/24/health/pregnant-brain-dead-woman-texas/index.html")
      normalization.alternateUrls === Set("http://rss.cnn.com/rss/cnn_health.rss")
      normalization.openGraphUrl === Some("http://www.cnn.com/2014/01/24/health/pregnant-brain-dead-woman-texas/index.html")
    }

    "parse doc with strange alternates" in {
      val scraped = fetch("secrefer.com.txt")
      val normalization = scraped.content.normalization
      normalization.canonicalUrl === None
      normalization.alternateUrls === Set()
      normalization.openGraphUrl === Some("http://www.secrefer.com/login/?next=/apply/company/11?location=random?location=random")
    }

    "parse doc with alternates" in {
      val scraped = fetch("cnn.uk.txt")
      val normalization = scraped.content.normalization
      normalization.canonicalUrl === Some("http://www.bbc.co.uk/news/technology-25233230")
      normalization.alternateUrls === Set("http://www.bbc.co.uk/news/technology-25233230", "http://www.bbc.com/news/technology-25233230")
      normalization.openGraphUrl === Some("http://www.bbc.co.uk/news/technology-25233230")
    }

    "ignore canonical URLs that are the page URL HTML-escaped twice" in {
      val longUrl = "http://www.livejournal.com/gsearch?engine=google&cx=partner-pub-5600223439108080%3A3711723852&cof=FORID%3A10&ie=UTF-8&q=test&sa=Search&siteurl="
      val scraped = fetch("double-escape.txt")
      val normalization = scraped.content.normalization
      normalization.canonicalUrl === None
    }

    "ignore canonical URLs that are the page URL with parameter values URL-encoded twice" in {
      val url = "https://soundcloud.com/search?q=%D8%AF%DB%8C%D8%A7%D9%84%D9%88%DA%AF"
      val scraped = fetch("double-encode.txt")
      val normalization = scraped.content.normalization
      normalization.canonicalUrl === None
    }

    "extract keywords" in {
      //      val extractor = setup("https://cnn.com/url2", "www.cnn.com.health.txt", Some(500))
      //      extractor.getCanonicalUrl("") === Some("http://www.cnn.com/video/data/2.0/video/us/2014/01/24/newday-live-larson-u-s-olympic-team-uniforms.cnn-ap.html")
      //      extractor.getLinks("canonical") === Set("http://www.cnn.com/video/data/2.0/video/us/2014/01/24/newday-live-larson-u-s-olympic-team-uniforms.cnn-ap.html")
      //      extractor.getMetadata("og:url") === Some("http://www.cnn.com/video/data/2.0/video/us/2014/01/24/newday-live-larson-u-s-olympic-team-uniforms.cnn-ap.html")
      //      extractor.getKeywords() !== None
      //      extractor.getKeywords().exists(_.contains("Olympics")) === true
      val scraped = fetch("www.cnn.com.health.txt")
      val normalization = scraped.content.normalization
      normalization.canonicalUrl === Some("http://www.cnn.com/video/data/2.0/video/us/2014/01/24/newday-live-larson-u-s-olympic-team-uniforms.cnn-ap.html")
      normalization.openGraphUrl === Some("http://www.cnn.com/video/data/2.0/video/us/2014/01/24/newday-live-larson-u-s-olympic-team-uniforms.cnn-ap.html")

      val keywords = scraped.content.keywords
      keywords !== None
      keywords.exists(_.contains("Olympics")) === true
    }
  }

}
