package com.keepit.rover.manager

import com.keepit.rover.article.{ EmbedlyArticle, ArticleKind, Article }
import org.joda.time.DateTime
import com.keepit.common.time._

import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import scala.util.Random

case class FetchSchedulingPolicy(
    maxRandomDelay: Duration,
    initialInterval: Duration,
    minInterval: Duration,
    maxInterval: Duration,
    intervalIncrement: Duration,
    intervalDecrement: Duration,
    maxBackoff: Duration,
    backoffIncrement: Duration) {

  private val maxRandomDelaySeconds: Int = maxRandomDelay.toSeconds.toInt
  private def randomDelay: Duration = Random.nextInt(maxRandomDelaySeconds) seconds

  def nextFetchAfterFailure(failureCount: Int): DateTime = {
    val backoff = (backoffIncrement * (1 << failureCount)) min maxBackoff
    val secondsToNextFetch = (backoff + randomDelay).toSeconds.toInt
    currentDateTime plusSeconds secondsToNextFetch
  }

  def nextFetchAfterSuccess(interval: Duration): DateTime = {
    val secondsToNextFetch = (interval + randomDelay).toSeconds.toInt
    currentDateTime plusMinutes secondsToNextFetch
  }

  def increaseInterval(currentInterval: Duration): Duration = (currentInterval - intervalDecrement) max minInterval
  def decreaseInterval(currentInterval: Duration): Duration = (currentInterval + intervalIncrement) min maxInterval
}

object FetchSchedulingPolicy {
  def apply[A <: Article](implicit kind: ArticleKind[A]): FetchSchedulingPolicy = {
    kind match {
      case EmbedlyArticle => embedlySchedulingPolicy
      case _ => defaultSchedulingPolicy
    }
  }

  private val embedlySchedulingPolicy = FetchSchedulingPolicy(
    maxRandomDelay = 1 hour,
    initialInterval = 14 days,
    minInterval = 7 days,
    maxInterval = 120 days,
    intervalIncrement = 0 days,
    intervalDecrement = 0 days,
    maxBackoff = 2 days,
    backoffIncrement = 6 hours
  )

  private val defaultSchedulingPolicy = FetchSchedulingPolicy(
    maxRandomDelay = 6 hours,
    initialInterval = 14 days,
    minInterval = 7 days,
    maxInterval = 120 days,
    intervalIncrement = 5 days,
    intervalDecrement = 5 days,
    maxBackoff = 40 days,
    backoffIncrement = 6 hours
  )
}
