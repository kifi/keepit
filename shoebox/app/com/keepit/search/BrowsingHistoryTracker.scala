package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model.{BrowsingHistoryRepo, User, NormalizedURI}
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.QueryHash
import java.io.File
import com.keepit.model.BrowsingHistory
import com.keepit.common.db.slick._
import com.keepit.shoebox.ShoeboxServiceClient
import com.google.inject.Inject

class BrowsingHistoryTracker @Inject() (tableSize: Int, numHashFuncs: Int, minHits: Int,
    browsingHistoryRepo: BrowsingHistoryRepo, db: Database, shoeboxClient: ShoeboxServiceClient) {

  def add(userId: Id[User], uriId: Id[NormalizedURI]) = {
    shoeboxClient.addBrowsingHistory(userId.id, uriId.id, tableSize, numHashFuncs, minHits)
  }

  def getMultiHashFilter(userId: Id[User]) = {
    db.readOnly { implicit session =>
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
