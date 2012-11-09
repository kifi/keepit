package com.keepit.search.line

import com.keepit.common.logging.Logging
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.Similarity
import org.apache.lucene.search.TermQuery
import org.apache.lucene.util.PriorityQueue
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions._

class LineQueryBuilder(similarity: Similarity, percentMatch: Float) extends Logging {

  val pctMatch = (if (percentMatch > 0.0f) percentMatch else Float.MinPositiveValue)
  
  def build(query: Query)(implicit indexReader: IndexReader): LineQuery = {
    query match {
      case q: TermQuery => translateTermQuery(q)
      case q: PhraseQuery => translatePhraseQuery(q)
      case q: BooleanQuery => translateBooleanQuery(q)
      case q: Query => translateOtherQuery(q)
    }
  }
  
  def translateTermQuery(query: TermQuery)(implicit indexReader: IndexReader) = {
    val term = query.getTerm
    val idf = similarity.idf(indexReader.docFreq(term), indexReader.numDocs)
    new TermNode(term, idf, indexReader)
  }

  def translatePhraseQuery(query: PhraseQuery)(implicit indexReader: IndexReader) = {
    val terms = query.getTerms()
    val numDocs = indexReader.numDocs
    val idf = terms.foldLeft(0.0f){ (idf, term) => idf + similarity.idf(indexReader.docFreq(term), numDocs)}
    val positions = query.getPositions();
    val nodes = terms.map{ term => new TermNode(term, 1, indexReader) }.toArray
    new PhraseNode(nodes, positions, idf, indexReader);
  }
  
  def translateBooleanQuery(query: BooleanQuery)(implicit indexReader: IndexReader) = {
    var required = new ArrayBuffer[Query]
    var prohibited = new ArrayBuffer[Query]
    var optional = new ArrayBuffer[Query]
    query.getClauses.foreach{ clause =>
      if(clause.isRequired) {
        required += clause.getQuery
      } else if(clause.isProhibited) {
        prohibited += clause.getQuery
      } else {
        optional += clause.getQuery
      }
    }
    val positiveExpr = if (required.size > 0) {
      new BooleanNode(translate(required), translate(optional), pctMatch, indexReader)
    } else {
      new BooleanOrNode(translate(optional), pctMatch, indexReader)
    }
    
    if (prohibited.size > 0) new BooleanNot(positiveExpr, translate(prohibited), indexReader)
    else positiveExpr
  }
  
  def translateOtherQuery(query: Query)(implicit indexReader: IndexReader) = {
    try {
      val terms = new java.util.HashSet[Term]()
      query.extractTerms(terms)
      val termNodes = terms.iterator.map{ term =>
        val idf = similarity.idf(indexReader.docFreq(term), indexReader.numDocs)
        new TermNode(term, idf, indexReader)
      }.toArray
      new BooleanOrNode(termNodes, pctMatch, indexReader)
    } catch {
      case _ =>
        log.warn("query translation failed: %s".format(query.getClass.toString))
        LineQuery.emptyQueryNode
    }
  }

  private def translate(queries: ArrayBuffer[Query])(implicit indexReader: IndexReader) = {
    queries.map{ query => build(query) }.toArray
  }
}