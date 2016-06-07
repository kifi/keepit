package com.keepit.search

import com.keepit.common.util.IdFilterCompressor
import com.keepit.search.util.LongArraySet
import com.kifi.macros.json

@json
case class SearchContext(context: Option[String], orderBy: SearchRanking, filter: SearchFilter, disablePrefixSearch: Boolean, disableFullTextSearch: Boolean) {
  lazy val idFilter: LongArraySet = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))
  val isDefault = filter.isDefault
}
