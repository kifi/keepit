package com.keepit.slack

import com.keepit.common.actor.TestKitSupport
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class SlackIngestingBlacklistTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {

  val blacklist = SlackIngestingBlacklist

  "SlackIngestingBlacklist" should {
    "parse raw blacklist strings" in {
      blacklist.parseBlacklist("") === Seq.empty
      blacklist.parseBlacklist("github.com") === Seq("github.com")
      blacklist.parseBlacklist("github.com,kifi.com") === Seq("github.com", "kifi.com")
      blacklist.parseBlacklist(" *.github.com, kifi.com ") === Seq("*.github.com", "kifi.com")

      // Filtering cases
      blacklist.parseBlacklist(",,,,") === Seq.empty
      blacklist.parseBlacklist("*******") === Seq.empty
      blacklist.parseBlacklist("a" * 100) === Seq()
    }

    "don't filter non-blacklisted domains" in {
      val reddington = Seq("github.com/kifi", "kifi.com/andrew/*/find", "*.asana.com", "fortytwo.airbrake.io/*")

      blacklist.blacklistedUrl("http://www.google.com/", reddington) === false
      blacklist.blacklistedUrl("http://github.com", reddington) === false
      blacklist.blacklistedUrl("https://asana.com/asdf", reddington) === false
      blacklist.blacklistedUrl("www.kifi.com/andrew", reddington) === false
    }

    "filter blacklisted domains" in {
      val reddington = Seq("github.com/kifi", "kifi.com/andrew/*/find", "*.asana.com", "fortytwo.airbrake.io/*")

      blacklist.blacklistedUrl("https://www.github.com/kifi/commits", reddington) === true
      blacklist.blacklistedUrl("https://github.com/kifi/commits", reddington) === true
      blacklist.blacklistedUrl("github.com/kifi", reddington) === true
      blacklist.blacklistedUrl("https://www.kifi.com/andrew/scala/find?q=a", reddington) === true
      blacklist.blacklistedUrl("https://app.asana.com/proj", reddington) === true
      blacklist.blacklistedUrl("https://fortytwo.airbrake.io/numbers", reddington) === true
      blacklist.blacklistedUrl("https://www.asana.com", reddington) === true
    }
  }
}
