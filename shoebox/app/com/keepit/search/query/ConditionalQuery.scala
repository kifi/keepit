package com.keepit.search.query

import org.apache.lucene.index.Term
import org.apache.lucene.search.Query
import org.apache.lucene.search.FilteredQuery
import org.apache.lucene.search.QueryWrapperFilter

class ConditionalQuery(val source: Query, val condition: Query) extends FilteredQuery(source, new QueryWrapperFilter(condition))