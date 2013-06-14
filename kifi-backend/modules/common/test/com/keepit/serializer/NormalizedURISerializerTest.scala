package com.keepit.serializer

import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import securesocial.core._
import com.keepit.model._

class NormalizedURISerializerTest extends Specification {

  "NormalizedURISerializer" should {
    "do a basic serialization flow only uri" in {
      val uri = NormalizedURIFactory("http://foo.bar.com/hi")
      val serializer = new NormalizedURISerializer()
      val json = serializer.writes(uri)
      val newUri = serializer.reads(json).get
      uri === newUri
    }

    "do a basic serialization flow title and uri" in {
      val uri = NormalizedURIFactory("Hi there!", "http://foo.bar.com/hi")
      val serializer = new NormalizedURISerializer()
      val json = serializer.writes(uri)
      val newUri = serializer.reads(json).get
      uri === newUri
    }
  }

}
