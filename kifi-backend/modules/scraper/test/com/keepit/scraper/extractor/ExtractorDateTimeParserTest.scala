package com.keepit.scraper.extractor

import org.joda.time.DateTime
import org.specs2.mutable._

class ExtractorDateTimeParserTest extends Specification {

  "ExtractorDateTimeParser" should {
    "parse various datetime strings" in {
      val datetime = DateTime.parse("2012-09-08T18:17:12")
      ExtractorDateTimeParser.parse("2012-09-08T18:17:12") === Some(datetime)
      ExtractorDateTimeParser.parse("2012-09-08 18:17:12") === Some(datetime)
      ExtractorDateTimeParser.parse("2012-09-08") === Some(DateTime.parse("2012-09-08T00:00:00"))
      ExtractorDateTimeParser.parse("20120908181712") === Some(datetime)
    }
  }
}
