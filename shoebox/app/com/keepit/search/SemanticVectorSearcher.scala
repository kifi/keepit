package com.keepit.search

import com.keepit.common.db._
import com.keepit.model._
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.index.Searcher
import com.keepit.search.graph.URIGraphSearcher
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import java.io.StringReader
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap


class SemanticVectorSearcher(articleSearcher: Searcher, uriGraphSearcher: URIGraphSearcher) {
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


  // used for friendMap
  def getSemanticVectors(userIds: Array[Id[User]], query: String, lang: Lang, minDocs: Int = 1): Map[Id[User],Array[SemanticVector]] = {
    val terms = getTerms(query, lang)
    userIds.foldLeft(Map.empty[Id[User],Array[SemanticVector]]){ (m, u) =>
      val uriIds = uriGraphSearcher.getUserToUriEdgeSet(u, publicOnly = false).destIdLongSet
      val vectors = terms.map{ term =>
        getSemanticVector(term, uriIds, minDocs) match {
          case Some(v) => v
          case None => new SemanticVector(Array.empty[Byte])
        }
      }
      if (vectors.forall(_.bytes.isEmpty)) m else m + (u -> vectors)
    }
  }

  /**
   * Given a term and a set of documents, we find the semantic vector for each
   * (term, document) pair.
   * Note: this may return empty map
   */
  def getSemanticVectors(term: Term, uriIds:Set[Long]): Map[Long, SemanticVector] = {

    val subReaders = indexReader.wrappedSubReaders
    var sv = Map[Long, SemanticVector]();
    var i = 0

    var earlyStop = false
    var docsRead = 0
    var docsToRead = uriIds.size

    while (i < subReaders.length && !earlyStop) {
      val subReader = subReaders(i)
      val idMapper = subReader.getIdMapper
      val tp = subReader.termPositionsEnum(term)
      if (tp != null) {
        // iterate over docs containing this term
        while (tp.nextDoc < NO_MORE_DOCS) {
          val id = idMapper.getId(tp.docID())
          if (uriIds.contains(id)) {
            docsRead += 1
            earlyStop = (docsRead == docsToRead)							// early stop: don't need to go through every subreader
            var vector = new SemanticVector(new Array[Byte](SemanticVector.arraySize))
            if (tp.freq() > 0){
              tp.nextPosition()
              val payload = tp.getPayload()
              vector.set(payload.bytes, payload.offset, payload.length)
              sv += (id -> vector)
            }
          }
        }
      }
      i += 1
    }
    sv
  }


  def getTerms(query: String, lang: Lang): Array[Term] = {
    val analyzer = DefaultAnalyzer.forParsingWithStemmer(lang).getOrElse(DefaultAnalyzer.forParsing(lang))
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
