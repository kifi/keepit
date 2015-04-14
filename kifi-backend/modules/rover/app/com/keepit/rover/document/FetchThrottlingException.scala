package com.keepit.rover.document

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache.{ Key, JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics }
import com.keepit.common.json.{ TraversableFormat, TupleFormat }
import com.keepit.common.logging.AccessLog
import com.keepit.common.net.URI
import com.keepit.common.time._
import org.joda.time.DateTime
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import play.api.libs.json.{ JsValue, Json, Format }

case class FetchThrottlingException(url: String, destinationUrl: String, nextFetch: DateTime)
  extends Exception(s"Throttling fetch of $url with destination $destinationUrl. Try again at $nextFetch")

object FetchThrottlingException {
  val domainWideThrottlingWindow = 1 minute
  val domainWideThrottlingLimit = 5
  val throttlingBackoff: Duration = 10 minutes
  val throttlingMaxRandomDelay: Duration = 10 minutes

  val randomDelay = new RandomDelay(throttlingMaxRandomDelay)

  def apply(url: String, destinationUrl: String): FetchThrottlingException = {
    val nextFetch: DateTime = {
      val secondsToNextFetch = (throttlingBackoff + randomDelay()).toSeconds.toInt
      currentDateTime plusSeconds secondsToNextFetch
    }
    FetchThrottlingException(url, destinationUrl, nextFetch)
  }
}

@Singleton
class DomainFetchThrottler @Inject() (
    recentFetchesCache: RecentFetchesDomainCache,
    implicit private val executionContext: ExecutionContext) {

  def throttle(destinationUrl: String): Boolean = {
    URI.parseDomain(destinationUrl).exists { domain =>
      val key = RecentFetchesDomainCacheKey(domain)
      val recentFetches = recentFetchesCache.direct.get(key) getOrElse RecentFetches.empty
      val shouldThrottle = recentFetches.count > FetchThrottlingException.domainWideThrottlingLimit
      if (!shouldThrottle) SafeFuture {
        recentFetchesCache.direct.set(key, recentFetches + (destinationUrl -> currentDateTime))
      }
      shouldThrottle
    }
  }
}

class RecentFetches(fetches: Set[(String, DateTime)], recencyWindow: Duration) {

  private def recencyLimit = currentDateTime minusSeconds recencyWindow.toSeconds.toInt

  def get: Set[(String, DateTime)] = fetches.filter(_._2 isAfter recencyLimit)

  def count: Int = get.size

  def +(fetch: (String, DateTime)): RecentFetches = new RecentFetches(fetches + fetch, recencyWindow)
}

object RecentFetches {
  val recencyWindow = FetchThrottlingException.domainWideThrottlingWindow
  def apply(fetches: Set[(String, DateTime)]): RecentFetches = new RecentFetches(fetches, recencyWindow)
  implicit val format: Format[RecentFetches] = {
    implicit val tupleFormat = TupleFormat.tuple2Format[String, DateTime]
    implicit val setFormat: Format[Set[(String, DateTime)]] = TraversableFormat.set[(String, DateTime)]
    new Format[RecentFetches] {
      def writes(recentFetches: RecentFetches) = Json.toJson(recentFetches.get)
      def reads(jsValue: JsValue) = jsValue.validate[Set[(String, DateTime)]].map(RecentFetches(_))
    }
  }

  val empty = RecentFetches(Set.empty)
}

case class RecentFetchesDomainCacheKey(domain: String) extends Key[RecentFetches] {
  override val version = 1
  val namespace = "recent_fetches_by_domain"
  def toKey(): String = domain
}

class RecentFetchesDomainCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[RecentFetchesDomainCacheKey, RecentFetches](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
