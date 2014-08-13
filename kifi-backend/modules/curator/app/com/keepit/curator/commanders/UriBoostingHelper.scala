package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.{ MultipliedSeedItem, SeedItem }
import com.keepit.model.SystemValueRepo

class UriBoostingHelper() {

  val penalizeScore = Seq(
    ("""(?i)\b(https?|ftp|file)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]*twitter.com[-A-Za-z0-9+&@#/%=~_|]""".r, 0.01f, "Twitter"),
    ("""(?i)\b(https?|ftp|file)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]*linkedin.com[-A-Za-z0-9+&@#/%=~_|]""".r, 0.01f, "LinkedIn"),
    ("""(?i)\b(https?|ftp|file)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]*mail.google.com[-A-Za-z0-9+&@#/%=~_|]""".r, 0.001f, "Google Mail"),
    ("""(?i)\b(https?|ftp|file)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]*wikipedia.org[-A-Za-z0-9+&@#/%=~_|]""".r, 0.1f, "Wikipedia"),
    ("""(?i)\b(https?|ftp|file)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]*facebook.com[-A-Za-z0-9+&@#/%=~_|]""".r, 0.001f, "Facebook")
  )

  val boostScore = Seq(
    ("""(?i)\b(https?|ftp|file)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]*techcrunch.com[-A-Za-z0-9+&@#/%=~_|]""".r, 1.2f, "Techcrunch")
  )

  def apply(items: Seq[SeedItem]): Seq[MultipliedSeedItem] = items.map { item =>
    var weight = 1.0f
    penalizeScore.foreach { reg =>
      weight = reg._1.findFirstIn(item.url) match {
        case Some(data) => reg._2
        case None => weight
      }
    }
    boostScore.foreach { reg =>
      weight = reg._1.findFirstIn(item.url) match {
        case Some(data) => reg._2
        case None => weight
      }
    }

    MultipliedSeedItem(
      multiplier = weight,
      userId = item.userId,
      uriId = item.uriId,
      priorScore = item.priorScore,
      timesKept = item.timesKept,
      lastSeen = item.lastSeen,
      keepers = item.keepers)
  }
}
