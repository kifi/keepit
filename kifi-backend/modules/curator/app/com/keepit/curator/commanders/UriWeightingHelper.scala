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
    //----------------------------------Penalize---------------------------------------------------------------------------
    UrlPattern("""^https?://[-A-Za-z0-9.]*kifi.com[./?\#]""".r, 0.0f, "Anything about kifi.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*42go.com[./?\#]""".r, 0.0f, "Anything about 42go.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*wikipedia.org[./?\#]""".r, 0.0f, "Anything about wikipedia.org"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*amazon.com[./?\#]""".r, 0.0f, "Anything about amazon.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*ebay.*[./?\#]""".r, 0.0f, "Anything about ebay"),//mark
    UrlPattern("""^https?://[-A-Za-z0-9.]*walmart.com[./?\#]""".r, 0.0f, "Anything about walmart.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*etsy.com[./?\#]""".r, 0.0f, "Anything about etsy.com"),
    UrlPattern("""^https?://google.com/calendar*""".r, 0.0f, "Google calendar"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*fedex.com[./?\#]""".r, 0.0f, "Anything about fedex.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*ups.com[./?\#]""".r, 0.0f, "Anything about ups.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*usps.gov[./?\#]""".r, 0.0f, "Anything about usps.gov"),
    UrlPattern("""^https?://mail.google.com[./?\#]""".r, 0.0f, "Anything about Gmail"),
    UrlPattern("""^https?://gmail.com[./?\#]""".r, 0.0f, "Anything about Gmail"),

    UrlPattern("""^https?://[-A-Za-z0-9.]*groupon.com[./?\#]""".r, 0.5f, "Anything about groupon.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*linkedin.com/profile[./?\#]""".r, 0.5f, "Linkedin profile pages"),
    UrlPattern("""^https?://[www?].twitter.com[./?\#]""".r, 0.5f, "Twitter feeds"),
    UrlPattern("""^https?://[www?].facebook.com[./?\#]""".r, 0.5f, "Facebook feeds"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*dropbox.com[./?\#]""".r, 0.5f, "dropbox.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*craigslist.com[./?\#]""".r, 0.5f, "craigslist.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*dropbox.com[./?\#]""".r, 0.5f, "Dropbox.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*[-A-Za-z0-9.]**[-A-Za-z0-9.][./?\#]support*""".r, 0.5f, "Anything with support"),//not sure
    UrlPattern("""^https?://(drive|docs|plus).google.com[./?\#]""".r, 0.5f, "google drive, docs and plus"),
    UrlPattern("""^https?://(images?|videos?|maps?|search|[www?]*).google.com[./?\#]""".r, 0.5f, "google search, images, videos and maps"),
    UrlPattern("""^https?://(images?|videos?|maps?|search|[www?]*).bing.com[./?\#]""".r, 0.5f, "bing search, images, videos and maps"),
    UrlPattern("""^https?://([images?|videos?|screen|maps?|search|answers|].|[www?.]?)yahoo.com[.\/?\#]?""".r, 0.5f, "yahoo search, images, videos, answer and maps"),
    UrlPattern("""^https?://[www?.]? """.r, 0.0f, ""),
    //----------------------------------Boost------------------------------------------------------------------------------

    UrlPattern("""^https?://[-A-Za-z0-9.]*medium.com[./?\#]""".r, 1.1f, "Anything about medium.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*longreads.com[./?\#]""".r, 1.1f, "Anything about longreads.com"),
    UrlPattern("""^https?://[www?.]?youtube.com[./?\#]watch""".r, 1.2f, "Videos on Youtube")
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

