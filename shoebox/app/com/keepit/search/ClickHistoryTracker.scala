package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model.{ClickHistory, ClickHistoryRepo, User, NormalizedURI}
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.QueryHash
import java.io.File
import com.keepit.model.ClickHistory
import com.keepit.common.db.slick._

class ClickHistoryTracker (tableSize: Int, numHashFuncs: Int, minHits: Int, repo: ClickHistoryRepo, db: Database) {

  def add(userId: Id[User], uriId: Id[NormalizedURI]) = {
    val filter = getMultiHashFilter(userId)
    filter.put(uriId.id)

    db.readWrite { implicit session =>
      repo.save(repo.getByUserId(userId) match {
        case Some(h) => h.withFilter(filter.getFilter)
        case None =>
          ClickHistory(userId = userId, tableSize = tableSize, filter = filter.getFilter, numHashFuncs = numHashFuncs, minHits = minHits)
      })
    }
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
