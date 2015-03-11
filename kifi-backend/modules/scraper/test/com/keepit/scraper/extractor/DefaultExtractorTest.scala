package com.keepit.scraper.extractor

import org.specs2.mutable._
import java.io.FileInputStream
import com.keepit.rover.fetcher.HttpInputStream
import com.keepit.common.net.URI

class DefaultExtractorTest extends Specification {

  def setup(url: String, file: String, maxContentChars: Option[Int] = None): DefaultExtractor = {
    val uri = URI.parse(url).get
    val stream = new FileInputStream("test/com/keepit/scraper/extractor/" + file)
    val extractor = maxContentChars match {
      case Some(max) => DefaultExtractorProvider(uri, max)
      case None => DefaultExtractorProvider(uri)
    }
    extractor.process(new HttpInputStream(stream))
    extractor
  }

  "DefaultExtractor" should {
    "parse doc 1" in {
      val extractor = setup("https://cnn.com/url1", "money.cnn.com.dimon-pay.txt")
      extractor.getCanonicalUrl("") === Some("http://money.cnn.com/2014/01/24/news/companies/dimon-pay/index.html")
      extractor.getLinks("canonical") === Set("http://money.cnn.com/2014/01/24/news/companies/dimon-pay/index.html")
      extractor.getLinks("alternate") === Set()
      extractor.getMetadata("og:url") === Some("http://money.cnn.com/2014/01/24/news/companies/dimon-pay/index.html")
    }

    "parse doc 2" in {
      val extractor = setup("https://cnn.com/url2", "www.cnn.com.health.txt")
      extractor.getCanonicalUrl("") === Some("http://www.cnn.com/video/data/2.0/video/us/2014/01/24/newday-live-larson-u-s-olympic-team-uniforms.cnn-ap.html")
      extractor.getLinks("canonical") === Set("http://www.cnn.com/video/data/2.0/video/us/2014/01/24/newday-live-larson-u-s-olympic-team-uniforms.cnn-ap.html")
      extractor.getLinks("alternate") === Set()
      extractor.getMetadata("og:url") === Some("http://www.cnn.com/video/data/2.0/video/us/2014/01/24/newday-live-larson-u-s-olympic-team-uniforms.cnn-ap.html")
    }

    "parse doc 3" in {
      val extractor = setup("https://cnn.com/url3", "www.cnn.com.pregnant-brain-dead-woman-texas.txt")
      extractor.getCanonicalUrl("") === Some("http://www.cnn.com/2014/01/24/health/pregnant-brain-dead-woman-texas/index.html")
      extractor.getLinks("canonical") === Set("http://www.cnn.com/2014/01/24/health/pregnant-brain-dead-woman-texas/index.html")
      extractor.getLinks("alternate") === Set("http://rss.cnn.com/rss/cnn_health.rss")
      extractor.getMetadata("og:url") === Some("http://www.cnn.com/2014/01/24/health/pregnant-brain-dead-woman-texas/index.html")
    }

    "parse doc with strange alternates" in {
      val extractor = setup("http://www.secrefer.com/login/?next=/apply/company/11", "secrefer.com.txt")
      extractor.getCanonicalUrl("") === None
      extractor.getLinks("canonical") === Set.empty
      extractor.getLinks("alternate") === Set.empty
      extractor.getMetadata("og:url") === Some("http://www.secrefer.com/login/?next=/apply/company/11?location=random?location=random")
    }

    "parse doc with alternates" in {
      val extractor = setup("http://www.bbc.co.uk/news/technology-25233230", "cnn.uk.txt")
      extractor.getCanonicalUrl("") === Some("http://www.bbc.co.uk/news/technology-25233230")
      extractor.getLinks("canonical") === Set("http://www.bbc.co.uk/news/technology-25233230")
      extractor.getLinks("alternate") === Set("http://www.bbc.co.uk/news/technology-25233230", "http://www.bbc.com/news/technology-25233230")
      extractor.getMetadata("og:url") === Some("http://www.bbc.co.uk/news/technology-25233230")
    }

    "ignore canonical URLs that are the page URL HTML-escaped twice" in {
      val longUrl = "http://www.livejournal.com/gsearch?engine=google&cx=partner-pub-5600223439108080%3A3711723852&cof=FORID%3A10&ie=UTF-8&q=test&sa=Search&siteurl="
      val extractor = setup(longUrl, "double-escape.txt")
      extractor.getCanonicalUrl(longUrl) === None
    }

    "stop when limit reached" in {
      val extractor = setup("https://cnn.com/url2", "www.cnn.com.health.txt", Some(500))
      extractor.getCanonicalUrl("") === Some("http://www.cnn.com/video/data/2.0/video/us/2014/01/24/newday-live-larson-u-s-olympic-team-uniforms.cnn-ap.html")
      extractor.getLinks("canonical") === Set("http://www.cnn.com/video/data/2.0/video/us/2014/01/24/newday-live-larson-u-s-olympic-team-uniforms.cnn-ap.html")
      extractor.getMetadata("og:url") === Some("http://www.cnn.com/video/data/2.0/video/us/2014/01/24/newday-live-larson-u-s-olympic-team-uniforms.cnn-ap.html")
      extractor.getKeywords() !== None
      extractor.getKeywords().exists(_.contains("Olympics")) === true
    }

  }
}
