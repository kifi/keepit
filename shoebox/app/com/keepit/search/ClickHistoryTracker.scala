package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model.{ClickHistory, ClickHistoryRepo, User, NormalizedURI}
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.QueryHash
import java.io.File
import com.keepit.inject._
import play.api.Play.current
import com.keepit.model.ClickHistory
import com.keepit.common.db.slick._

object ClickHistoryTracker {
  def apply(filterSize: Int, numHashFuncs: Int, minHits: Int) = {
    new ClickHistoryTracker(filterSize, numHashFuncs, minHits)
  }
}

class ClickHistoryTracker (tableSize: Int, numHashFuncs: Int, minHits: Int) {

  def add(userId: Id[User], uriId: Id[NormalizedURI]) = {
    val filter = getMultiHashFilter(userId)
    filter.put(uriId.id)

    val repo = inject[ClickHistoryRepo]
    inject[Database].readWrite { implicit session =>
      repo.save(repo.getByUserId(userId) match {
        case Some(h) => h.withFilter(filter.getFilter)
        case None =>
          ClickHistory(userId = userId, tableSize = tableSize, filter = filter.getFilter, numHashFuncs = numHashFuncs, minHits = minHits)
      })
    }
  }

  def getMultiHashFilter(userId: Id[User]) = {
    val repo = inject[ClickHistoryRepo]
    inject[Database].readOnly { implicit session =>
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
