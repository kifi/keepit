package com.keepit.curator.commanders

import com.keepit.curator.model.{ WeightedSeedItem, SeedItem }
import com.google.inject.{ Singleton }

import scala.util.matching.Regex

case class UrlPattern(
  regex: Regex,
  weight: Float,
  description: String)

@Singleton
class UriWeightingHelper() {

  val scoringMultiplier = Seq(
    //----------------------------------Penalize---------------------------------------------------------------------------
    UrlPattern("""^https?://[-A-Za-z0-9.]*twitter.com[./?\#]""".r, 0.01f, "Twitter"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*linkedin.com[./?\#]""".r, 0.01f, "LinkedIn"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*mail.google.com[./?\#]""".r, 0.001f, "Google Mail"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*wikipedia.org[./?\#]""".r, 0.1f, "Wikipedia"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*facebook.com[./?\#]""".r, 0.001f, "Facebook"),

    //----------------------------------Boost------------------------------------------------------------------------------
    UrlPattern("""^https?://[-A-Za-z0-9.]*techcrunch.com[./?\#]""".r, 1.2f, "Techcrunch")
  )

  def apply(items: Seq[SeedItem]): Seq[WeightedSeedItem] = items.map { item =>
    val weight = scoringMultiplier.find(pattern => pattern.regex.findFirstIn(item.url).isDefined) match {
      case Some(pattern) => pattern.weight
      case None => 1.0f
    }

    WeightedSeedItem(
      weightMultiplier = weight,
      userId = item.userId,
      uriId = item.uriId,
      priorScore = item.priorScore,
      timesKept = item.timesKept,
      lastSeen = item.lastSeen,
      keepers = item.keepers)
  }
}
