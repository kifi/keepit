package com.keepit.commanders

import com.keepit.common.api.FakeUriShortner
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class TwitterMessagesTest extends Specification {

  "TwitterMessages" should {

    "parseHandleFromUrl" in {
      val messages = new TwitterMessages(new FakeUriShortner())
      messages.parseHandleFromUrl("https://www.twitter.com/eishay") === "@eishay"
    }

    "keepMessage short" in {
      val messages = new TwitterMessages(new FakeUriShortner())
      Await.result(messages.keepMessage("short keep", "http://keep.com/1234", "short lib", "http://lib.com/12345"), Duration.Inf) === "short keep http://keep.com/1234 kept to short lib http://lib.com/12345 via @kifi"
    }

    "keepMessage long keep" in {
      val messages = new TwitterMessages(new FakeUriShortner())
      val res = "long keep with lots of text that should be trimmed down so ... http://goo.gl/uxgdgy kept to short lib http://goo.gl/uxgdgy via @kifi"
      Await.result(messages.keepMessage("long keep with lots of text that should be trimmed down so the more then 140 chars", "http://keep.com/1234", "short lib", "http://lib.com/12345"), Duration.Inf) === res
    }

    "keepMessage long keep long lib" in {
      val messages = new TwitterMessages(new FakeUriShortner())
      val res = "long keep with lots of text that should be trim... http://goo.gl/uxgdgy kept to a long lib title t... http://goo.gl/uxgdgy via @kifi"
      Await.result(messages.keepMessage("long keep with lots of text that should be trimmed down so the more then 140 chars", "http://keep.com/1234", "a long lib title that has some meaning", "http://lib.com/12345"), Duration.Inf) === res
    }

    "keepMessage short keep short lib url" in {
      val messages = new TwitterMessages(new FakeUriShortner())
      val res = "short keep http://keep.com/1234 kept to a long lib title that has some meaning and more! http://lib.com/12345 via @kifi"
      Await.result(messages.keepMessage("short keep", "http://keep.com/1234", "a long lib title that has some meaning and more!", "http://lib.com/12345"), Duration.Inf) === res
    }

    "keepMessage long keep long url" in {
      val messages = new TwitterMessages(new FakeUriShortner())
      val res = "short keep http://goo.gl/uxgdgy kept to a long lib title that has some meaning and more! http://lib.com/12345 via @kifi"
      Await.result(messages.keepMessage("short keep", "http://keep.com/asdf/asd/fas/dfa/sdf/1234/asdf/asf", "a long lib title that has some meaning and more!", "http://lib.com/12345"), Duration.Inf) === res
    }
  }
}
