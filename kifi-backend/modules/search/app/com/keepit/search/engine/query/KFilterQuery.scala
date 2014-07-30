package com.keepit.search.engine.query

import org.apache.lucene.index.Term
import org.apache.lucene.search.TermQuery
import org.apache.lucene.util.ToStringUtils

trait KFilterQuery

object KSiteQuery {
  def apply(domain: String) = {
    val tok = domain.toLowerCase.dropWhile { c => !c.isLetterOrDigit }
    if (tok.length > 0) {
      new KSiteQuery(new Term("site", tok))
    } else {
      null
    }
  }
}

class KSiteQuery(term: Term) extends TermQuery(term) with KFilterQuery {
  override def toString(s: String) = "site(%s:%s)%s".format(term.field(), term.text(), ToStringUtils.boost(getBoost()))
}

object KMediaQuery {
  // mediaType: e.g. "pdf", "mp3", "movie", "music"
  def apply(mediaType: String) = {
    if (mediaType.length > 0) {
      new KMediaQuery(new Term("media", mediaType))
    } else {
      null
    }
  }
}

class KMediaQuery(term: Term) extends TermQuery(term) with KFilterQuery {
  override def toString(s: String) = "media(%s:%s)%s".format(term.field(), term.text(), ToStringUtils.boost(getBoost()))
}
