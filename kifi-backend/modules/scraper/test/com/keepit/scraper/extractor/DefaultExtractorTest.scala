package com.keepit.scraper.extractor

import org.specs2.mutable._
import java.io.FileInputStream
import com.keepit.scraper.HttpInputStream
import com.keepit.common.net.URI

class DefaultExtractorTest extends Specification {

  def setup(url: String, file: String): DefaultExtractor = {
    val uri = URI.parse(url).get
    val stream = new FileInputStream("modules/scraper/test/com/keepit/scraper/extractor/" + file)
    val extractor = DefaultExtractorProvider(uri)
    extractor.process(new HttpInputStream(stream))
    extractor
  }

  "DefaultExtractor" should {
    "parse doc 1" in {
      val extractor = setup("https://cnn.com/url1", "money.cnn.com.dimon-pay.txt")
      extractor.getCanonicalUrl() === Some("http://money.cnn.com/2014/01/24/news/companies/dimon-pay/index.html")
      extractor.getLink("canonical") === Some("http://money.cnn.com/2014/01/24/news/companies/dimon-pay/index.html")
      extractor.getMetadata("og:url") === Some("http://money.cnn.com/2014/01/24/news/companies/dimon-pay/index.html")
      extractor.getMetadata("vr:canonical") === None
    }

    "parse doc 2" in {
      val extractor = setup("https://cnn.com/url2", "www.cnn.com.health.txt")
      extractor.getCanonicalUrl() === Some("http://www.cnn.com/video/data/2.0/video/us/2014/01/24/newday-live-larson-u-s-olympic-team-uniforms.cnn-ap.html")
      extractor.getLink("canonical") === Some("http://www.cnn.com/video/data/2.0/video/us/2014/01/24/newday-live-larson-u-s-olympic-team-uniforms.cnn-ap.html")
      extractor.getMetadata("og:url") === Some("http://www.cnn.com/video/data/2.0/video/us/2014/01/24/newday-live-larson-u-s-olympic-team-uniforms.cnn-ap.html")
      extractor.getMetadata("vr:canonical") === None
    }

    "parse doc 3" in {
      val extractor = setup("https://cnn.com/url3", "www.cnn.com.pregnant-brain-dead-woman-texas.txt")
      extractor.getCanonicalUrl() === Some("http://www.cnn.com/2014/01/24/health/pregnant-brain-dead-woman-texas/index.html")
      extractor.getLink("canonical") === Some("http://www.cnn.com/2014/01/24/health/pregnant-brain-dead-woman-texas/index.html")
      extractor.getMetadata("og:url") === Some("http://www.cnn.com/2014/01/24/health/pregnant-brain-dead-woman-texas/index.html")
      extractor.getMetadata("vr:canonical") === Some("http://www.cnn.com/2014/01/24/health/pregnant-brain-dead-woman-texas/index.html")
    }

  }
}


