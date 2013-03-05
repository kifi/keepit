package com.keepit.search

import com.keepit.common.db._
import com.keepit.model._
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.index.Searcher
import com.keepit.search.graph.URIGraphSearcher
import org.apache.lucene.index.Term
import com.keepit.search.index.DefaultAnalyzer
import java.io.StringReader
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import scala.collection.mutable.ArrayBuffer

class SemanticVectorSearcher(articleSearcher: Searcher, uriGraphSearcher: URIGraphSearcher) {
  val indexReader = articleSearcher.indexReader

  private def getSemanticVector(term: Term, ids: Set[Long], minDocs: Int = 1): Option[Array[Byte]] = {
    val subReaders = indexReader.wrappedSubReaders
    val composer = new SemanticVectorComposer
    var vector = new Array[Byte](SemanticVector.arraySize)
    var i = 0
    while (i < subReaders.length) {
      val subReader = subReaders(i)
      val idMapper = subReader.getIdMapper
      val tp = subReader.termPositions(term)
      try {
        while (tp.next) {
          val id = idMapper.getId(tp.doc())
          if (ids.contains(id)) {
            var freq = tp.freq()
            while (freq > 0) {
              freq -= 1
              tp.nextPosition()
              vector = tp.getPayload(vector, 0)
              composer.add(vector, 1)
            }
          }
        }
      } finally {
        tp.close()
      }
      i += 1
    }
    if (composer.numInputs >= minDocs) Some(composer.getSemanticVector()) else None
  }

  def getSemanticVectors(userIds: Array[Id[User]], query: String, lang: Lang, minDocs: Int = 1): Map[Id[User],Array[Array[Byte]]] = {
    val terms = getTerms(query, lang)
    userIds.foldLeft(Map.empty[Id[User],Array[Array[Byte]]]){ (m, u) =>
      val uriIds = uriGraphSearcher.getUserToUriEdgeSet(u, publicOnly = false).destIdLongSet
      val vectors = terms.map{ term =>
        getSemanticVector(term, uriIds, minDocs) match {
          case Some(v) => v
          case None => Array.empty[Byte]
        }
      }
      if (vectors.forall(_.isEmpty)) m else m + (u -> vectors)
    }
  }

  def getTerms(query: String, lang: Lang): Array[Term] = {
    val analyzer = DefaultAnalyzer.forParsingWithStemmer(lang).getOrElse(DefaultAnalyzer.forParsing(lang))
    val ts = analyzer.tokenStream("b", new StringReader(query))
    val termAttr = ts.getAttribute(classOf[CharTermAttribute])
    val buf = new ArrayBuffer[Term]
    while (ts.incrementToken()) {
      buf += new Term("sv", new String(termAttr.buffer(), 0, termAttr.length()))
    }
    buf.toArray
  }
}
