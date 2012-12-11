package com.keepit.search.query

import org.apache.lucene.index.Term
import org.apache.lucene.search.TermQuery

object SiteQuery {
  def apply(domain: String) = {
    val tok = domain.toLowerCase.dropWhile{ c => ! c.isLetterOrDigit }
    new SiteQuery(new Term("site", tok))
  }
}

class SiteQuery(term: Term) extends TermQuery(term)