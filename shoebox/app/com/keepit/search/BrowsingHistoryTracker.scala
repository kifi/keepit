package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model.{BrowsingHistoryRepo, User, NormalizedURI}
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.QueryHash
import java.io.File
import com.keepit.inject._
import play.api.Play.current
import com.keepit.model.BrowsingHistory
import com.keepit.common.db.slick._

object BrowsingHistoryTracker {
  def apply(filterSize: Int, numHashFuncs: Int, minHits: Int) = {
    new BrowsingHistoryTracker(filterSize, numHashFuncs, minHits)
  }
}

class BrowsingHistoryTracker(tableSize: Int, numHashFuncs: Int, minHits: Int) {

  def add(userId: Id[User], uriId: Id[NormalizedURI]) = {
    val browsingHistoryRepo = inject[BrowsingHistoryRepo]
    val filter = getMultiHashFilter(userId)
    filter.put(uriId.id)

    inject[Database].readWrite { implicit session =>
      val browsingHistoryRepo = inject[BrowsingHistoryRepo]
      browsingHistoryRepo.save(browsingHistoryRepo.getByUserId(userId) match {
        case Some(bh) =>
          bh.withFilter(filter.getFilter)
        case None =>
          BrowsingHistory(userId = userId, tableSize = tableSize, filter = filter.getFilter, numHashFuncs = numHashFuncs, minHits = minHits)
      })
    }
  }

  def getMultiHashFilter(userId: Id[User]) = {
    val browsingHistoryRepo = inject[BrowsingHistoryRepo]
    inject[Database].readOnly { implicit session =>
      browsingHistoryRepo.getByUserId(userId) match {
        case Some(browsingHistory) =>
          new MultiHashFilter(browsingHistory.tableSize, browsingHistory.filter, browsingHistory.numHashFuncs, browsingHistory.minHits)
        case None =>
          val filter = MultiHashFilter(tableSize, numHashFuncs, minHits)
          filter
      }
    }
  }
}
