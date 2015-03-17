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
    UrlPattern("""^https?://[-A-Za-z0-9.]*kifi\.com[./?\#]?""".r, 0.0f, "Anything about kifi.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*42go\.com[./?\#]?""".r, 0.0f, "Anything about 42go.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*wikipedia\.org[./?\#]?""".r, 0.0f, "Anything about wikipedia.org"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*amazon\.(.*)""".r, 0.0f, "Anything about amazon.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*ebay\.(.*)""".r, 0.0f, "Anything about ebay"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*walmart\.com[./?\#]?""".r, 0.0f, "Anything about walmart.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*etsy\.com[./?\#]?""".r, 0.0f, "Anything about etsy.com"),
    UrlPattern("""^https?://(www.)?google\.com/calendar(.*)""".r, 0.0f, "Google calendar"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*fedex\.com[./?\#]?""".r, 0.0f, "Anything about fedex.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*ups\.com[./?\#]?""".r, 0.0f, "Anything about ups.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*usps\.(gov|com)[./?\#]?""".r, 0.0f, "Anything about usps.gov"),
    UrlPattern("""^https?://(mail.)?(google|gmail)\.com[./?\#]?""".r, 0.0f, "Anything about Gmail"),

    UrlPattern("""^https?://[-A-Za-z0-9.]*techcrunch\.com[./?\#]?""".r, 0.3f, "Anything techcrunch.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*recode\.net[./?\#]?""".r, 0.3f, "Anything recode.net"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*macworld\.com[./?\#]?""".r, 0.3f, "Anything macworld.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*macrumors\.com[./?\#]?""".r, 0.3f, "Anything macrumors.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*pando\.com[./?\#]?""".r, 0.3f, "Anything pando.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*valleywag\.gawker\.com[./?\#]?""".r, 0.3f, "Anything Valleywag"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*theverge\.com[./?\#]?""".r, 0.3f, "Anything theverge.com"),

    UrlPattern("""^https?://[-A-Za-z0-9.]*groupon\.com[./?\#]?""".r, 0.5f, "Anything about groupon.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*linkedin\.com/profile[./?\#]?""".r, 0.5f, "Linkedin profile pages"),
    UrlPattern("""^https?://(www.|)twitter\.com[./?\#]""".r, 0.5f, "Twitter feeds"),
    UrlPattern("""^https?://(www.|)facebook\.com[./?\#]""".r, 0.5f, "Facebook feeds"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*dropbox\.com[./?\#]?""".r, 0.5f, "dropbox.com"),
    UrlPattern("""^https?://[-A-Za-z0-9.]*craigslist\.(org|com)[./?\#]?""".r, 0.5f, "craigslist.com"),
    UrlPattern("""^https?://[A-Za-z0-9]+\.[A-Za-z0-9]+\.[A-Za-z0-9]+[./?\#]?(support|help)""".r, 0.5f, "Anything with support"), //not sure
    UrlPattern("""^https?://(drive|docs|plus)\.google\.com""".r, 0.5f, "google drive, docs and plus"),
    UrlPattern("""^https?://(((images?.|videos?.|maps?.|search.)?google\.com[./?\#]?)|(www.)?google\.com[./?\#](q|search))""".r, 0.5f, "google search, images, videos and maps"),
    UrlPattern("""^https?://(((images?.|videos?.|maps?.|search.)?bing\.com[./?\#]?)|(www.)?bing\.com[./?\#].*search)""".r, 0.5f, "bing search, images, videos and maps"),
    UrlPattern("""^https?://(images?.|videos?.|screen.|maps?.|search.|answers.|www.)?yahoo\.com[.\/?\#]?""".r, 0.5f, "yahoo search, images, videos, answer and maps"),
    UrlPattern("""^https?://[A-Za-z0-9]+\.[A-Za-z0-9]+\.[A-Za-z0-9]+[./?\#]?\.*(homepage|index|home)""".r, 0.8f, "Any home page"),

    // tmp penalty required by product
    UrlPattern("""^https?://blog\.bufferapp\.com/.*""".r, 0.5f, "buffer blog"),
    UrlPattern("""^https?://www\.maclife\.com/.*""".r, 0.5f, "maclife"),

    //----------------------------------Boost------------------------------------------------------------------------------
    UrlPattern("""^https?://(www.)?tripadvisor\.com[./?\#][-A-Za-z0-9.]?""".r, 1.1f, "Destination page on tripadvisor"),
    UrlPattern("""^https?://(www.)?medium\.com[./?\#][-A-Za-z0-9.]?""".r, 1.1f, "Article on medium.com"),
    UrlPattern("""^https?://blog\.longreads\.com[./?\#][-A-Za-z0-9.]?""".r, 1.1f, "Article on longreads.com"),
    UrlPattern("""^https?://(www.)?youtube\.com[./?\#]watch""".r, 1.1f, "Videos on Youtube")
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

