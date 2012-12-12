package com.keepit.search.query

import org.apache.lucene.index.Term
import org.apache.lucene.search.TermQuery
import org.apache.lucene.util.ToStringUtils

object SiteQuery {
  def apply(domain: String) = {
    val tok = domain.toLowerCase.dropWhile{ c => ! c.isLetterOrDigit }
    new SiteQuery(new Term("site", tok))
  }
}

class SiteQuery(term: Term) extends TermQuery(term) {
  override def toString(s: String) = "site(%s:%s)%s".format(term.field(), term.text(), ToStringUtils.boost(getBoost()))
}