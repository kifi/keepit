package com.keepit.scraper

import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.URI
import com.keepit.shoebox.ShoeboxScraperClient

import scala.concurrent.Future
import scala.concurrent.{ ExecutionContext => ScalaExecutionContext }

class URICommander @Inject() (
    shoeboxScraperClient: ShoeboxScraperClient,
    airbrake: AirbrakeNotifier,
    implicit val executionContext: ScalaExecutionContext) {

  def isNonSensitive(url: String): Future[Boolean] = try {
    shoeboxScraperClient.getAllURLPatterns().map { patterns =>
      val pat = patterns.rules.find(rule => url.matches(rule.pattern))
      pat.exists(_.nonSensitive)
    }
  } catch {
    case e: Exception =>
      airbrake.notify(s"bypassing exception from shoebox, assuming url is not Sensitive", e)
      Future.successful(true)
  }

  def isUnscrapable(url: String, destinationUrl: Option[String]): Future[Boolean]  = try {
    shoeboxScraperClient.getAllURLPatterns() map { rules =>
      rules.isUnscrapable(url) || destinationUrl.exists(rules.isUnscrapable)
    }
  } catch {
    case e: Exception =>
      airbrake.notify(s"bypassing exception from shoebox, assuming url is scrapable", e)
      Future.successful(false)
  }

}
