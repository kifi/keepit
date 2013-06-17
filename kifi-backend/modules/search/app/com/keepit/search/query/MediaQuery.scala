package com.keepit.search.query

import org.apache.lucene.index.Term
import org.apache.lucene.search.TermQuery
import org.apache.lucene.util.ToStringUtils

object MediaQuery {
  // mediaType: e.g. "pdf", "mp3", "movie", "music"
  def apply(mediaType: String) = {
    if (mediaType.length > 0) {
      new MediaQuery(new Term("media", mediaType))
    } else {
      null
    }
  }
}

class MediaQuery(term: Term) extends TermQuery(term) {
  override def toString(s: String) = "media(%s:%s)%s".format(term.field(), term.text(), ToStringUtils.boost(getBoost()))
}