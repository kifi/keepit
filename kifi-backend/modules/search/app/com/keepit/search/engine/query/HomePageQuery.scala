package com.keepit.search.engine.query

import org.apache.lucene.index.Term
import org.apache.lucene.search.{ Query, PhraseQuery, TermQuery }

class HomePageQuery private (subQuery: Query) extends FixedScoreQuery(subQuery) {
  def this(terms: Seq[Term]) = {
    this(
      if (terms.size == 1) {
        new TermQuery(new Term("home_page", terms(0).text))
      } else {
        val q = new PhraseQuery()
        terms.foreach { t => q.add(new Term("home_page", t.text)) }
        q
      }
    )
  }
}
