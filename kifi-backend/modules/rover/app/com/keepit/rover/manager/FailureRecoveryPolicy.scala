package com.keepit.rover.manager

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.time._
import com.keepit.rover.document.FetchThrottlingException
import org.joda.time.DateTime

import scala.concurrent.duration.Duration
import scala.concurrent.duration._

object FailureRecoveryPolicy {
  val maxBackoff: Duration = 2 days
  val backoffIncrement: Duration = 6 hours
  val maxRandomDelay: Duration = 1 hour
  val maxAttempts = 3
}

@Singleton
class FailureRecoveryPolicy @Inject() () {

  import FailureRecoveryPolicy._

  private val randomDelay = new RandomDelay(maxRandomDelay)

  private def defaultNextFetch(failureCount: Int): Option[DateTime] = if (failureCount < maxAttempts) Some {
    val backoff = (backoffIncrement * (1 << failureCount)) min maxBackoff
    val secondsToNextFetch = (backoff + randomDelay()).toSeconds.toInt
    currentDateTime plusSeconds secondsToNextFetch
  }
  else None

  def nextFetch(url: String, error: Throwable, failureCount: Int): Option[DateTime] = error match {
    case throttlingException: FetchThrottlingException => Some(throttlingException.nextFetch)
    case _ => defaultNextFetch(failureCount) // todo(Léo): be smarter, use "do not scrape" rules here vs in ArticleFetchPolicy?
  }
}
