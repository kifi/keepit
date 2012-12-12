package com.keepit.search.query

import org.apache.lucene.index.Term
import org.apache.lucene.search.Query
import org.apache.lucene.search.FilteredQuery
import org.apache.lucene.search.QueryWrapperFilter
import org.apache.lucene.util.ToStringUtils

class ConditionalQuery(val source: Query, val condition: Query) extends FilteredQuery(source, new QueryWrapperFilter(condition)) {
  override def toString(s: String) = {
    "conditional(%s, %s)%s".format(source.toString(s), condition.toString(s), ToStringUtils.boost(getBoost()))
  }
}