package com.keepit.search

import com.keepit.search.graph.collection.CollectionSearcherWithUser
import com.keepit.search.index.WrappedIndexReader

class PersonalizedSearcher(
  override val indexReader: WrappedIndexReader,
  val collectionSearcher: CollectionSearcherWithUser) extends Searcher(indexReader)
