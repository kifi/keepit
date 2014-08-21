package com.keepit.curator.commanders

import com.keepit.curator.model.{ PublicSeedItemWithMultiplier, PublicSeedItem, SeedItemWithMultiplier, SeedItem }
import com.google.inject.{ Singleton }

import scala.util.matching.Regex

case class UrlPattern(
  regex: Regex,
  weight: Float,
  description: String)

object UrlPatterns {
  val scoringMultiplier = Seq(
    //----------------------------------Default---------------------------------------------------------------------------
    UrlPattern("""default""".r, 1.0f, "Default"),

    //----------------------------------Penalize---------------------------------------------------------------------------
    UrlPattern("""^https?://[-A-Za-z0-9.]*twitter.com[./?\#]""".r, 0.01f, "Twitter"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*linkedin.com[./?\#]""".r, 0.01f, "LinkedIn"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*mail.google.com[./?\#]""".r, 0.001f, "Google Mail"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*wikipedia.org[./?\#]""".r, 0.1f, "Wikipedia"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*facebook.com[./?\#]""".r, 0.001f, "Facebook"),

    //----------------------------------Boost------------------------------------------------------------------------------
    UrlPattern("""^https?://[-A-Za-z0-9.]*techcrunch.com[./?\#]""".r, 1.2f, "Techcrunch"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*.[-A-Za-z0-9.]*.[./?\#]kifi[-A-Za-z0-9.]*""".r, 1.2f, "Public page relate to Kifi"),
    UrlPattern("""^https?://(code|blog|engineering).[-A-Za-z0-9.]*.[./?\#][-A-Za-z0-9.]*""".r, 1.1f, "Tech")
  )
}

class UriWeightingHelper() {

  def apply(items: Seq[SeedItem]): Seq[SeedItemWithMultiplier] = items.map { item =>
    val masterWeight = UrlPatterns.scoringMultiplier.foldLeft(1.0f) { (weight, pattern) =>
      if (pattern.regex.findFirstIn(item.url).isDefined) weight * pattern.weight else weight
    }
    SeedItemWithMultiplier(
      multiplier = masterWeight,
      userId = item.userId,
      uriId = item.uriId,
      priorScore = item.priorScore,
      timesKept = item.timesKept,
      lastSeen = item.lastSeen,
      keepers = item.keepers)
  }
}

class PublicUriWeightingHelper() {

  def apply(items: Seq[PublicSeedItem]): Seq[PublicSeedItemWithMultiplier] = items.map { item =>
    val masterWeight = UrlPatterns.scoringMultiplier.foldLeft(1.0f) { (weight, pattern) =>
      if (pattern.regex.findFirstIn(item.url).isDefined) weight * pattern.weight else weight
    }

    PublicSeedItemWithMultiplier(
      multiplier = masterWeight,
      uriId = item.uriId,
      timesKept = item.timesKept,
      lastSeen = item.lastSeen,
      keepers = item.keepers)
  }
}

