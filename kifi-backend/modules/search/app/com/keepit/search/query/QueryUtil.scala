package com.keepit.search.query

import com.keepit.common.logging.Logging
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.index.DocsEnum
import org.apache.lucene.index.DocsAndPositionsEnum
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.DocIdSet
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.Weight
import org.apache.lucene.util.Bits
import org.apache.lucene.util.BytesRef
import java.util.{ HashSet => JHashSet }
import scala.collection.JavaConversions._
import org.apache.lucene.analysis.Analyzer
import java.io.StringReader
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import scala.collection.mutable.ListBuffer

object QueryUtil extends Logging {

  def getTerms(fieldName: String, query: Query): Set[Term] = {
    getTerms(query).filter { _.field() == fieldName }
  }

  def getTerms(fieldNames: Set[String], query: Query): Set[Term] = {
    getTerms(query).filter { term => fieldNames.contains(term.field()) }
  }

  def getTerms(query: Query): Set[Term] = {
    try {
      val terms = new JHashSet[Term]()
      query.extractTerms(terms)
      terms.toSet
    } catch {
      case _: Throwable =>
        log.warn("term extraction failed: %s".format(query.getClass.toString))
        Set.empty[Term]
    }
  }

  def emptyScorer(weight: Weight) = new Scorer(weight) {
    override def score(): Float = 0.0f
    override def docID() = NO_MORE_DOCS
    override def nextDoc(): Int = NO_MORE_DOCS
    override def advance(target: Int): Int = NO_MORE_DOCS
    override def freq() = 0
    override def cost(): Long = 0L
  }

  def copy(query: TermQuery, field: String): Query = {
    if (query == null) null
    else {
      val term = new Term(field, query.getTerm().text())
      val newQuery = new TermQuery(term)
      newQuery.setBoost(query.getBoost())
      newQuery
    }
  }

  def copy(query: PhraseQuery, field: String): Query = {
    if (query == null) null
    else {
      val newQuery = new PhraseQuery()
      val positions = query.getPositions()
      val terms = query.getTerms()
      terms.zip(positions).foreach { case (t, p) => newQuery.add(new Term(field, t.text()), p) }
      newQuery.setBoost(query.getBoost())
      newQuery
    }
  }

  def copy(query: BooleanQuery, field: String): Query = {
    if (query == null) null
    else {
      val newQuery = new BooleanQuery()
      val clauses = query.getClauses()
      clauses.map { clause =>
        val newSubQuery = clause.getQuery() match {
          case null => null
          case subq: TermQuery => copy(subq, field)
          case subq: PhraseQuery => copy(subq, field)
          case subq: BooleanQuery => copy(subq, field)
          case subq => throw new Exception(s"failed to copy query: ${subq.toString}")
        }
        newQuery.add(newSubQuery, clause.getOccur())
      }
      newQuery.setBoost(query.getBoost())
      newQuery
    }
  }

  def termDocsEnum(context: AtomicReaderContext, term: Term, acceptDocs: Bits): DocsEnum = {
    val fields = context.reader.fields()
    if (fields != null) {
      val terms = fields.terms(term.field())
      if (terms != null) {
        val termsEnum = terms.iterator(null)
        if (termsEnum.seekExact(term.bytes())) {
          return termsEnum.docs(acceptDocs, null)
        }
      }
    }
    return null
  }

  def termPositionsEnum(context: AtomicReaderContext, term: Term, acceptDocs: Bits): DocsAndPositionsEnum = {
    val fields = context.reader.fields()
    if (fields != null) {
      val terms = fields.terms(term.field())
      if (terms != null) {
        val termsEnum = terms.iterator(null)
        if (termsEnum.seekExact(term.bytes())) {
          return termsEnum.docsAndPositions(acceptDocs, null)
        }
      }
    }
    return null
  }

  def filteredTermPositionsEnum(tp: DocsAndPositionsEnum, docIdSet: DocIdSet): DocsAndPositionsEnum = {
    if (docIdSet == null || tp == null) return null

    val iter = docIdSet.iterator()
    if (iter == null) return null

    new DocsAndPositionsEnum {
      override def docID() = tp.docID()
      override def freq() = tp.freq()
      override def nextDoc(): Int = {
        iter.nextDoc()
        join()
      }
      override def advance(did: Int): Int = {
        iter.advance(did)
        join()
      }
      private def join(): Int = {
        while (iter.docID != tp.docID) {
          if (iter.docID < tp.docID) iter.advance(tp.docID) else tp.advance(iter.docID)
        }
        iter.docID
      }
      override def nextPosition(): Int = tp.nextPosition()
      override def startOffset(): Int = tp.startOffset()
      override def endOffset(): Int = tp.endOffset()
      override def getPayload(): BytesRef = tp.getPayload()
      override def cost(): Long = 0L
    }
  }

  object EmptyDocsAndPositionsEnum extends DocsAndPositionsEnum {
    override def docID() = NO_MORE_DOCS
    override def freq() = 0
    override def nextDoc(): Int = NO_MORE_DOCS
    override def advance(did: Int): Int = NO_MORE_DOCS
    override def nextPosition(): Int = 0
    override def startOffset(): Int = -1
    override def endOffset(): Int = -1
    override def getPayload(): BytesRef = null
    override def cost(): Long = 0L
  }

  def emptyDocIdSetIterator: DocIdSetIterator = EmptyDocsAndPositionsEnum
  def emptyDocsEnum: DocsEnum = EmptyDocsAndPositionsEnum
  def emptyDocsAndPositionsEnum: DocsAndPositionsEnum = EmptyDocsAndPositionsEnum

  def getTermOffsets(analyzer: Analyzer, queryText: String) = {
    val ts = analyzer.tokenStream("foo", new StringReader(queryText))
    val offset = ts.addAttribute(classOf[OffsetAttribute])
    val startOffsets = ListBuffer.empty[(Int, Int)]
    try {
      ts.reset()
      while (ts.incrementToken()) {
        startOffsets.append((offset.startOffset, offset.endOffset))
      }
      ts.end()
    } finally {
      ts.close()
    }
    startOffsets.toSeq
  }
}
