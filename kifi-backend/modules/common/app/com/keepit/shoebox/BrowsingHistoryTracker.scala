package com.keepit.shoebox

import com.keepit.common.db.Id
import com.keepit.model.{BrowsingHistoryRepo, User, NormalizedURI}
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.QueryHash
import java.io.File
import com.keepit.model.BrowsingHistory
import com.keepit.common.db.slick._
import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.search.MultiHashFilter
import com.keepit.model.BrowsingHistory

class BrowsingHistoryTracker (tableSize: Int, numHashFuncs: Int, minHits: Int,
    browsingHistoryRepo: BrowsingHistoryRepo, db: Database) extends Logging{

  def add(userId: Id[User], uriId: Id[NormalizedURI]) = {
    val filter = getMultiHashFilter(userId)
    filter.put(uriId.id)

    db.readWrite { implicit session =>
      browsingHistoryRepo.save(browsingHistoryRepo.getByUserId(userId) match {
        case Some(bh) =>
          bh.withFilter(filter.getFilter)
        case None =>
          BrowsingHistory(userId = userId, tableSize = tableSize, filter = filter.getFilter, numHashFuncs = numHashFuncs, minHits = minHits)
      })
    }
  }

  def getMultiHashFilter(userId: Id[User]): MultiHashFilter[BrowsingHistory] = {
    db.readOnly { implicit session =>
      browsingHistoryRepo.getByUserId(userId) match {
        case Some(browsingHistory) =>
          new MultiHashFilter[BrowsingHistory](browsingHistory.tableSize, browsingHistory.filter, browsingHistory.numHashFuncs, browsingHistory.minHits)
        case None =>
          val filter = MultiHashFilter[BrowsingHistory](tableSize, numHashFuncs, minHits)
          filter
      }
    }
  }
}
