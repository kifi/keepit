package com.keepit.serializer

import org.specs2.mutable._
import com.keepit.model._

class NormalizedURISerializerTest extends Specification {

  "NormalizedURISerializer" should {
    "do a basic serialization flow only uri" in {
      val uri = NormalizedURI.withHash(normalizedUrl = "http://foo.bar.com/hi")
      val serializer = NormalizedURI.format
      val json = serializer.writes(uri)
      val newUri = serializer.reads(json).get
      uri === newUri
    }

    "do a basic serialization flow title and uri" in {
      val uri = NormalizedURI.withHash(normalizedUrl = "http://foo.bar.com/hi", title = Some("Hi there!"))
      val serializer = NormalizedURI.format
      val json = serializer.writes(uri)
      val newUri = serializer.reads(json).get
      uri === newUri
    }
  }
}
