package com.keepit.scraper

import org.specs2.mutable.Specification
import org.apache.http.HttpStatus.{SC_MOVED_PERMANENTLY => MOVED, SC_MOVED_TEMPORARILY => TEMP_MOVED}
import HttpRedirect._

class HttpRedirectTest extends Specification {
  "HttpRedirect" should {

    "Standardize relative redirects" in {
      withStandardizationEffort(MOVED, "https://www.oracle.com/technology/index.html", "/technetwork/index.html") === HttpRedirect(MOVED, "https://www.oracle.com/technology/index.html", "https://www.oracle.com/technetwork/index.html")
    }

    "Find the correct permanent destination url after multiple redirects" in {
      resolvePermanentRedirects("http://www.lemonde.fr", Seq.empty) === None
      resolvePermanentRedirects("http://www.bbc.co.uk", Seq(HttpRedirect(MOVED, "http://www.bbc.co.uk", "http://www.bbc.com"))) === Some("http://www.bbc.com")

      val threePermanentRedirects = Seq(
        HttpRedirect(MOVED, "http://www.oracle.com/technology/index.html", "https://www.oracle.com/technology/index.html"),
        HttpRedirect(MOVED, "https://www.oracle.com/technology/index.html", "https://www.oracle.com/technetwork/index.html"),
        HttpRedirect(MOVED, "https://www.oracle.com/technetwork/index.html", "http://www.oracle.com/technetwork/index.html")
      )
      resolvePermanentRedirects("http://www.oracle.com/technology/index.html", threePermanentRedirects) === Some("http://www.oracle.com/technetwork/index.html")
      resolvePermanentRedirects("http://www.oracle.com", threePermanentRedirects) === None

      val withOneTemporaryRedirect = Seq(
        HttpRedirect(MOVED, "http://www.oracle.com/technology/index.html", "https://www.oracle.com/technology/index.html"),
        HttpRedirect(TEMP_MOVED, "https://www.oracle.com/technology/index.html", "https://www.oracle.com/technetwork/index.html"),
        HttpRedirect(MOVED, "https://www.oracle.com/technetwork/index.html", "http://www.oracle.com/technetwork/index.html")
      )

      resolvePermanentRedirects("http://www.oracle.com/technology/index.html", withOneTemporaryRedirect) === Some("https://www.oracle.com/technology/index.html")

      val withOneRelativeRedirect= Seq(
        HttpRedirect(MOVED, "http://www.oracle.com/technology/index.html", "https://www.oracle.com/technology/index.html"),
        HttpRedirect(MOVED, "https://www.oracle.com/technology/index.html", "/technetwork/index.html")
      )

      resolvePermanentRedirects("http://www.oracle.com/technology/index.html", withOneRelativeRedirect) === Some("https://www.oracle.com/technology/index.html")

      val withOneRecoverableRelativeRedirect= Seq(
        HttpRedirect(MOVED, "http://www.oracle.com/technology/index.html", "https://www.oracle.com/technology/index.html"),
        HttpRedirect(MOVED, "https://www.oracle.com/technology/index.html", "/technetwork/index.html"),
        HttpRedirect(MOVED, "/technetwork/index.html", "http://www.oracle.com/technetwork/index.html")
      )

      resolvePermanentRedirects("http://www.oracle.com/technology/index.html", withOneRecoverableRelativeRedirect) === Some("http://www.oracle.com/technetwork/index.html")
    }
  }
}
