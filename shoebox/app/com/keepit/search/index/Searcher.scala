package com.keepit.search.index

import org.apache.lucene.index.IndexReader
import org.apache.lucene.search.Query

class Searcher(val indexReader: IndexReader, val idMapper: IdMapper) {
  def search(query: Query, f:(Int, Float) => Unit) {
    // TODO
  }
}
