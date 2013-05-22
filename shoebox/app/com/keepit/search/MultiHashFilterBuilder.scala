package com.keepit.search

import com.google.inject.Inject

trait MultiHashFilterBuilder {
  val tableSize: Int
  val numHashFuncs: Int
  val minHits: Int
  
  def build(filter: Array[Byte]) =
    new MultiHashFilter(tableSize, filter, numHashFuncs, minHits)
}

class ClickHistoryBuilder @Inject() (val tableSize: Int, val numHashFuncs: Int, val minHits: Int) extends MultiHashFilterBuilder
class BrowsingHistoryBuilder @Inject() (val tableSize: Int, val numHashFuncs: Int, val minHits: Int) extends MultiHashFilterBuilder
