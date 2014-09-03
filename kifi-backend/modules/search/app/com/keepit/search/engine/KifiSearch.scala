package com.keepit.search

import com.keepit.search.article.ArticleRecord
import com.keepit.search.graph.keep.{ KeepFields, KeepRecord }
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.{ TermQuery, BooleanQuery }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS

class KifiSearchUtil(articleSearcher: Searcher, keepSearcher: Searcher) {

  def getKeepRecord(keepId: Long)(implicit decode: (Array[Byte], Int, Int) => KeepRecord): Option[KeepRecord] = {
    keepSearcher.getDecodedDocValue[KeepRecord](KeepFields.recordField, keepId)
  }

  def getKeepRecord(libId: Long, uriId: Long)(implicit decode: (Array[Byte], Int, Int) => KeepRecord): Option[KeepRecord] = {
    val q = new BooleanQuery()
    q.add(new TermQuery(new Term(KeepFields.uriField, libId.toString)), Occur.MUST)
    q.add(new TermQuery(new Term(KeepFields.libraryField, uriId.toString)), Occur.MUST)

    keepSearcher.search(q) { (scorer, reader) =>
      if (scorer.nextDoc() < NO_MORE_DOCS) {
        return keepSearcher.getDecodedDocValue(KeepFields.recordField, reader, scorer.docID())
      }
    }
    None
  }

  def getArticleRecord(uriId: Long): Option[ArticleRecord] = {
    import com.keepit.search.article.ArticleRecordSerializer._
    articleSearcher.getDecodedDocValue[ArticleRecord]("rec", uriId)
  }
}
