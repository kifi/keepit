package com.keepit.search.engine.query

import com.keepit.common.logging.Logging
import org.apache.lucene.index._
import org.apache.lucene.search._
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.util.Bits
import org.apache.lucene.util.BytesRef
import java.util.{ HashSet => JHashSet }

import scala.collection.JavaConversions._

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

  def termPositionsEnum(context: LeafReaderContext, term: Term, acceptDocs: Bits): PostingsEnum = {
    val fields = context.reader.fields()
    if (fields != null) {
      val terms = fields.terms(term.field())
      if (terms != null) {
        val termsEnum = terms.iterator(null)
        if (termsEnum.seekExact(term.bytes())) {
          return termsEnum.postings(acceptDocs, null, PostingsEnum.POSITIONS)
        }
      }
    }
    return null
  }

  object EmptyDocsAndPositionsEnum extends PostingsEnum {
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
  def emptyDocsEnum: PostingsEnum = EmptyDocsAndPositionsEnum
  def emptyDocsAndPositionsEnum: PostingsEnum = EmptyDocsAndPositionsEnum
}
