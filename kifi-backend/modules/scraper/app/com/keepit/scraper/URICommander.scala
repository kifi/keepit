package com.keepit.scraper

import com.google.inject.Inject
import com.keepit.common.net.URI
import com.keepit.shoebox.ShoeboxScraperClient

import scala.concurrent.Future
import scala.concurrent.{ ExecutionContext => ScalaExecutionContext }

class URICommander @Inject() (
    shoeboxScraperClient: ShoeboxScraperClient,
    implicit val executionContext: ScalaExecutionContext) {

  def isNonSensitive(url: String): Future[Boolean] = {
    shoeboxScraperClient.getAllURLPatterns().map { patterns =>
      val pat = patterns.rules.find(rule => url.matches(rule.pattern))
      pat.exists(_.nonSensitive)
    }
  }

  def isUnscrapable(url: String, destinationUrl: Option[String]): Future[Boolean] = {
    shoeboxScraperClient.getAllURLPatterns() map { rules =>
      rules.isUnscrapable(url) || destinationUrl.exists(rules.isUnscrapable)
    }
  }

}
