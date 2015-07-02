package com.keepit.rover.fetcher.apache

import org.specs2.mutable.Specification

class ContentAwareHttpRequestExecutorTest extends Specification {

  "ContentAwareHttpRequestExecutor" should {
    "Ignore content" in {
      val executor = new ContentAwareHttpRequestExecutor()
      executor.parsableContent(Seq[String]()) === true
      executor.parsableContent(Seq("application/pdf")) === true
      executor.parsableContent(Seq("text/html")) === true
      executor.parsableContent(Seq("audio/ogg")) === false
    }
  }
}
