package com.keepit.search.article

import org.apache.lucene.index.Term
import com.keepit.search.query.QueryUtil
import org.apache.lucene.search.DocIdSetIterator
import com.keepit.search.index.WrappedSubReader

object ArticleVisibility {
  private[this] val fieldName = "visibility"
  val restrictedTerm = new Term(fieldName, "redirect")      // "redirect" is misleading. It's really "restricted", because of fishy 301, or porn, etc. Should change this when we reindexing

  @inline
  def apply(reader: WrappedSubReader): ArticleVisibility = {
    val iter: DocIdSetIterator = {
      val it = reader.termDocsEnum(restrictedTerm)
      if (it == null) QueryUtil.emptyDocsEnum else it
    }
    new ArticleVisibility(iter)
  }
}

class ArticleVisibility(val iter: DocIdSetIterator) extends AnyVal {

  def isVisible(doc: Int): Boolean = {
    if (iter.docID < doc) iter.advance(doc)

    (iter.docID > doc)
  }
}
