package com.keepit.search.article

import org.apache.lucene.index.Term
import com.keepit.search.query.QueryUtil
import org.apache.lucene.search.DocIdSetIterator
import com.keepit.search.index.WrappedSubReader

object ArticleVisibility {
  private[this] val fieldName = "visibility"
  val redirectTerm = new Term(fieldName, "redirect")
}

class ArticleVisibility(reader: WrappedSubReader) {
  import ArticleVisibility._

  private[this] val redirect: DocIdSetIterator = {
    val it = reader.termDocsEnum(redirectTerm)
    if (it == null) QueryUtil.emptyDocsEnum else it
  }

  def isVisible(doc: Int): Boolean = {
    if (redirect.docID < doc) redirect.advance(doc)

    (redirect.docID > doc)
  }
}
