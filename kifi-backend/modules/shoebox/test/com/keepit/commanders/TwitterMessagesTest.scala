package com.keepit.commanders

import org.specs2.mutable.Specification

class TwitterMessagesTest extends Specification {

  "TwitterMessages" should {

    "parseHandleFromUrl" in {
      val messages = new TwitterMessages()
      messages.parseHandleFromUrl("https://www.twitter.com/eishay") === "@eishay"
    }

    "keepMessage short" in {
      val messages = new TwitterMessages()
      messages.keepMessage("short keep", "http://keep.com/1234", "short lib", "http://lib.com/12345") === "short keep http://keep.com/1234 kept to short lib http://lib.com/12345 via @kifi"
    }

    "keepMessage long keep" in {
      val messages = new TwitterMessages()
      val res = "long keep with lots of text that should be trimmed down so the m... http://keep.com/1234 kept to short lib http://lib.com/12345 via @kifi"
      res.length === 137
      messages.keepMessage("long keep with lots of text that should be trimmed down so the more then 140 chars", "http://keep.com/1234", "short lib", "http://lib.com/12345") === res
    }

    "keepMessage long keep long lib" in {
      val messages = new TwitterMessages()
      val res = "long keep with lots of text that should be trimmed do... http://keep.com/1234 kept to a long lib title ... http://lib.com/12345 via @kifi"
      res.length === 137
      messages.keepMessage("long keep with lots of text that should be trimmed down so the more then 140 chars", "http://keep.com/1234", "a long lib title that has some meaning", "http://lib.com/12345") === res
    }

    "keepMessage short keep long lib" in {
      val messages = new TwitterMessages()
      val res = "short keep http://keep.com/1234 kept to a long lib title that has some meaning and more! http://lib.com/12345 via @kifi"
      res.length === 119
      messages.keepMessage("short keep", "http://keep.com/1234", "a long lib title that has some meaning and more!", "http://lib.com/12345") === res
    }
  }
}
