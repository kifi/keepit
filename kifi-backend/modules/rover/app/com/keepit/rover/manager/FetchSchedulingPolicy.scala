package com.keepit.rover.manager

import com.keepit.rover.article.{ ArticleKind, Article }

import scala.concurrent.duration.Duration
import scala.concurrent.duration._

case class FetchSchedulingPolicy(
  maxRandomDelay: Duration,
  initialInterval: Duration,
  minInterval: Duration,
  maxInterval: Duration,
  intervalIncrement: Duration,
  intervalDecrement: Duration,
  maxBackoff: Duration,
  backoffIncrement: Duration)

object FetchSchedulingPolicy {
  def apply[A <: Article](implicit kind: ArticleKind[A]): FetchSchedulingPolicy = {
    kind match {
      case _ => defaultSchedulingPolicy
    }
  }

  private val defaultSchedulingPolicy = FetchSchedulingPolicy(
    maxRandomDelay = 6 hours,
    initialInterval = 14 days,
    minInterval = 7 days,
    maxInterval = 120 days,
    intervalIncrement = 2 days,
    intervalDecrement = 2 days,
    maxBackoff = 40 days,
    backoffIncrement = 6 hours
  )
}
