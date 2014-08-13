package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.{ MultipliedSeedItem, SeedItem }
import com.keepit.model.SystemValueRepo

class UriBoostingHelper @Inject() (
    systemValueRepo: SystemValueRepo,
    db: Database) {

  val decreaseWeight = Seq(
    ("(aaa)".r, -100f),
    ("bbb".r, -2f))

  val increaseWeight = Seq(
    ("ccc".r, 100f),
    ("ddd".r, 5f)
  )

  def apply(items: Seq[SeedItem]): Seq[MultipliedSeedItem] = {

    items.map { item =>
      var weight = 1.0f
      decreaseWeight.foreach { reg =>
        weight += ((reg._1.findFirstMatchIn(item.url)) match {
          case Some(data) => reg._2
        })
      }
      increaseWeight.foreach { reg =>
        weight += ((reg._1.findFirstMatchIn(item.url)) match {
          case Some(data) => reg._2
        })
      }

      MultipliedSeedItem(
        multiplier = weight,
        userId = item.userId,
        uriId = item.uriId,
        seq = item.seq,
        priorScore = item.priorScore,
        timesKept = item.timesKept,
        lastSeen = item.lastSeen,
        keepers = item.keepers,
        discoverable = item.discoverable)
    }
  }
}
