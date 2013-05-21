package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model.{ClickHistory, ClickHistoryRepo, User, NormalizedURI}
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.QueryHash
import java.io.File
import com.keepit.model.ClickHistory
import com.keepit.common.db.slick._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.logging.Logging

class ClickHistoryTracker (tableSize: Int, numHashFuncs: Int, minHits: Int, repo: ClickHistoryRepo, db: Database, shoeboxClient: ShoeboxServiceClient) extends Logging {

  def add(userId: Id[User], uriId: Id[NormalizedURI]) = {
    log.info(s"adding clicking browsing for userId = ${userId.id}, uriId = ${uriId.id}")
    shoeboxClient.addClickingHistory(userId.id, uriId.id, tableSize, numHashFuncs, minHits)
  }

  def getMultiHashFilter(userId: Id[User]) = {
    db.readOnly { implicit session =>
      repo.getByUserId(userId) match {
        case Some(clickHistory) =>
          new MultiHashFilter(clickHistory.tableSize, clickHistory.filter, clickHistory.numHashFuncs, clickHistory.minHits)
        case None =>
          val filter = MultiHashFilter(tableSize, numHashFuncs, minHits)
          filter
      }
    }
  }
}
