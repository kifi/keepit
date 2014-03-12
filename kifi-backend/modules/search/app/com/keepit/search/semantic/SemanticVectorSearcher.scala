package com.keepit.search.semantic

import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.Searcher
import com.keepit.search.graph.bookmark.URIGraphSearcher
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import java.io.StringReader
import scala.collection.mutable.ArrayBuffer
import com.keepit.search.Lang
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute


class SemanticVectorSearcher(articleSearcher: Searcher) {
  val indexReader = articleSearcher.indexReader

  private def getSemanticVector(term: Term, ids: Set[Long], minDocs: Int = 1): Option[SemanticVector] = {
    val subReaders = indexReader.wrappedSubReaders
    val composer = new SemanticVectorComposer
    var vector = new SemanticVector(new Array[Byte](SemanticVector.arraySize))
    var i = 0
    while (i < subReaders.length) {
      val subReader = subReaders(i)
      val idMapper = subReader.getIdMapper
      val tp = subReader.termPositionsEnum(term)
      if (tp != null) {
        while (tp.nextDoc() < NO_MORE_DOCS) {
          val id = idMapper.getId(tp.docID())
          if (ids.contains(id)) {
            var freq = tp.freq()
            while (freq > 0) {
              freq -= 1
              tp.nextPosition()
              val payload = tp.getPayload()
              vector.set(payload.bytes, payload.offset, payload.length)
              composer.add(vector, 1)
            }
          }
        }
      }
      i += 1
    }
    if (composer.numInputs >= minDocs) Some(composer.getSemanticVector()) else None
  }

  def getTerms(query: String, lang: Lang): Array[Term] = {
    val analyzer = DefaultAnalyzer.getAnalyzerWithStemmer(lang)
    val ts = analyzer.tokenStream("b", new StringReader(query))
    val termAttr = ts.getAttribute(classOf[CharTermAttribute])
    val buf = new ArrayBuffer[Term]
    ts.reset()
    while (ts.incrementToken()) {
      buf += new Term("sv", new String(termAttr.buffer(), 0, termAttr.length()))
    }
    buf.toArray
  }
}
