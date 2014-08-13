package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.{ MultipliedSeedItem, SeedItem }
import com.keepit.model.SystemValueRepo

class UriBoostingHelper @Inject() (
    systemValueRepo: SystemValueRepo,
    db: Database) {

  val decreaseWeight = Seq(
    ("""^https?://(www.)?twitter.com/(#!/)?([^/]+)(/w+)*$""".r, 0.0001f, "Twitter"),
    ("""/(ftp|http|https)://?((www|ww).)?linkedin.com(w+:{0,1}w*@)?(S+)(:([0-9])+)?(/|/([w#!:.?+=&%@!-/]))?/""".r, 0.0001f, "LinkedIn"),
    ("""/^(http://|https://)?(?:www.)?facebook.com/(?:(?:w.)*#!/)?(?:pages/)?(?:[w-.]*/)*([w-.]*)/""".r, 0.0001f, "Facebook")
  )

  val increaseWeight = Seq(
    ("hackernews".r, 100f, "Hackernews")
  )

  def apply(items: Seq[SeedItem]): Seq[MultipliedSeedItem] = {

    items.map { item =>
      var weight = 1.0f
      decreaseWeight.foreach { reg =>
        weight += ((reg._1.findFirstMatchIn(item.url)) match {
          case Some(data) => reg._2
          case None => 0.0f
        })
      }
      increaseWeight.foreach { reg =>
        weight += ((reg._1.findFirstMatchIn(item.url)) match {
          case Some(data) => reg._2
          case None => 0.0f
        })
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
}
