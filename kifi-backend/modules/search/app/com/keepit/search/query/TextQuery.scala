package com.keepit.search.query

import org.apache.lucene.index.Term
import org.apache.lucene.search.DisjunctionMaxQuery
import org.apache.lucene.search.Query
import scala.collection.mutable.ArrayBuffer

class TextQuery extends DisjunctionMaxQuery(0.5f) {
  var terms: Array[Term] = TextQuery.noTerms
  var stems: Array[Term] = TextQuery.noTerms

  private[this] var collectionIds: Set[Long] = Set()

  val concatStems: ArrayBuffer[String] = ArrayBuffer()

  def add(query: Query, boost: Float) {
    query.setBoost(boost)
    add(query)
  }

  def addCollectionQuery(collectionId: Long) = {
    if (!collectionIds.contains(collectionId)) {
      add(new CollectionQuery(collectionId))
      collectionIds += collectionId
    }
  }
}

object TextQuery {
  val noTerms: Array[Term] = Array()
}

