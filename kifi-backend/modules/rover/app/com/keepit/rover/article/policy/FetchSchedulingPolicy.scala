package com.keepit.rover.article.policy

import com.keepit.common.time._
import com.keepit.rover.article.{ Article, ArticleKind, EmbedlyArticle }
import org.joda.time.DateTime

import scala.concurrent.duration.{ Duration, _ }

case class FetchSchedulingPolicy(
    maxRandomDelay: Duration,
    initialInterval: Duration,
    minInterval: Duration,
    maxInterval: Duration,
    intervalIncrement: Duration,
    intervalDecrement: Duration) {

  private val randomDelay = new RandomDelay(maxRandomDelay)

  def nextFetch(interval: Duration): DateTime = {
    val secondsToNextFetch = (interval + randomDelay()).toSeconds.toInt
    currentDateTime plusSeconds secondsToNextFetch
  }

  def decreaseInterval(currentInterval: Duration): Duration = minInterval max (currentInterval - intervalDecrement) min maxInterval
  def increaseInterval(currentInterval: Duration): Duration = minInterval max (currentInterval + intervalIncrement) min maxInterval
}

object FetchSchedulingPolicy {
  def apply[A <: Article](implicit kind: ArticleKind[A]): FetchSchedulingPolicy = {
    kind match {
      case EmbedlyArticle => embedlySchedulingPolicy
      case _ => defaultSchedulingPolicy
    }
  }

  private val embedlySchedulingPolicy = FetchSchedulingPolicy(
    maxRandomDelay = 10 days,
    initialInterval = 14 days,
    minInterval = 7 days,
    maxInterval = 120 days,
    intervalIncrement = 0 days,
    intervalDecrement = 0 days
  )

  private val defaultSchedulingPolicy = FetchSchedulingPolicy(
    maxRandomDelay = 10 days,
    initialInterval = 14 days,
    minInterval = 7 days,
    maxInterval = 120 days,
    intervalIncrement = 5 days,
    intervalDecrement = 5 days
  )
}
