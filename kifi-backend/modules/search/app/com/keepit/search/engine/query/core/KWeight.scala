package com.keepit.search.engine.query.core

import org.apache.lucene.search.Weight

import scala.collection.mutable.ArrayBuffer

trait KWeight {
  def getWeights(out: ArrayBuffer[(Weight, Float)]): Unit
}
