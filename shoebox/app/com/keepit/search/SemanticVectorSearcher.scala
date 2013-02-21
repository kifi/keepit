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

  def getSemanticVector(terms: Set[Term], ids: Set[Long]): Option[Array[Byte]] = {
    val subReaders = indexReader.wrappedSubReaders
    val composer = new SemanticVectorComposer
    var vector = new Array[Byte](SemanticVector.arraySize)
    terms.foreach{ term =>
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
    }
    if (composer.numInputs > 0) Some(composer.getSemanticVector()) else None
  }

  def getSemanticVector(userId: Id[User], query: String, lang: Lang): Option[Array[Byte]] = {
    val terms = getTerms(query, lang)
    val uriIds = uriGraphSearcher.getUserToUriEdgeSet(userId, publicOnly = false).destIdLongSet
    getSemanticVector(terms, uriIds)
  }

  def getSemanticVectors(userIds: Array[Id[User]], query: String, lang: Lang): Map[Id[User],Array[Byte]] = {
    val terms = getTerms(query, lang)
    userIds.foldLeft(Map.empty[Id[User],Array[Byte]]){ (m, u) =>
      val uriIds = uriGraphSearcher.getUserToUriEdgeSet(u, publicOnly = false).destIdLongSet
      getSemanticVector(terms, uriIds) match {
        case Some(v) => m + (u -> v)
        case None => m
      }
    }
  }

  def getTerms(query: String, lang: Lang): Set[Term] = {
    val analyzer = DefaultAnalyzer.forParsingWithStemmer(lang).getOrElse(DefaultAnalyzer.forParsing(lang))
    val ts = analyzer.tokenStream("b", new StringReader(query))
    val termAttr = ts.getAttribute(classOf[CharTermAttribute])
    val buf = new ArrayBuffer[Term]
    while (ts.incrementToken()) {
      buf += new Term("sv", new String(termAttr.buffer(), 0, termAttr.length()))
    }
    buf.toSet
  }
}
