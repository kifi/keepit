package com.keepit.rover.extractor.utils

import org.joda.time.DateTime
import org.specs2.mutable._

class DateTimeMetadataParserTest extends Specification {

  "ExtractorDateTimeParser" should {
    "parse various datetime strings" in {
      val datetime = DateTime.parse("2012-09-08T18:17:12")
      DateTimeMetadataParser.parse("2012-09-08T18:17:12") === Some(datetime)
      DateTimeMetadataParser.parse("2012-09-08 18:17:12") === Some(datetime)
      DateTimeMetadataParser.parse("2012-09-08") === Some(DateTime.parse("2012-09-08T00:00:00"))
      DateTimeMetadataParser.parse("20120908181712") === Some(datetime)
    }
  }
}
