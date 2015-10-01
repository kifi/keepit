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
  def get[A <: Article](implicit kind: ArticleKind[A]): Option[FetchSchedulingPolicy] = {
    kind match {
      case EmbedlyArticle => None // Embedly is special cased to save on API calls, refreshes are triggered on content changes detected by our own scrapers (see ArticleInfoRepo.onLatestArticle)
      case _ => Some(defaultSchedulingPolicy)
    }
  }

  private val defaultSchedulingPolicy = FetchSchedulingPolicy(
    maxRandomDelay = 30 days,
    initialInterval = 14 days,
    minInterval = 7 days,
    maxInterval = 120 days,
    intervalIncrement = 5 days,
    intervalDecrement = 5 days
  )
  
  val embedlyRefreshOnContentChangeIfOlderThan: Duration = 1 day
}
