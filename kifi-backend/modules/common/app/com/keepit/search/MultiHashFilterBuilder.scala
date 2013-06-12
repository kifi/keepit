package com.keepit.search

import com.google.inject.Inject
import com.keepit.model.ClickHistory
import com.keepit.model.BrowsingHistory

trait MultiHashFilterBuilder[T] {
  val tableSize: Int
  val numHashFuncs: Int
  val minHits: Int
  
  def build(filter: Array[Byte]) =
    new MultiHashFilter[T](tableSize, filter, numHashFuncs, minHits)
}

class ClickHistoryBuilder (val tableSize: Int, val numHashFuncs: Int, val minHits: Int) extends MultiHashFilterBuilder[ClickHistory]
class BrowsingHistoryBuilder (val tableSize: Int, val numHashFuncs: Int, val minHits: Int) extends MultiHashFilterBuilder[BrowsingHistory]
