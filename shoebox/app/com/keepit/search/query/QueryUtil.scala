package com.keepit.search.query

import com.keepit.common.logging.Logging
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.index.DocsEnum
import org.apache.lucene.index.DocsAndPositionsEnum
import com.keepit.search.index.Searcher
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.index.ReaderUtil
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.Weight
import org.apache.lucene.util.Bits
import java.util.{HashSet => JHashSet}
import scala.collection.JavaConversions._
import org.apache.lucene.util.BytesRef
import org.apache.lucene.search.DocIdSetIterator

object QueryUtil extends Logging {

  def getTerms(fieldName: String, query: Query): Set[Term] = {
    getTerms(query).filter{ _.field() == fieldName }
  }

  def getTerms(fieldNames: Set[String], query: Query): Set[Term] = {
    getTerms(query).filter{ term => fieldNames.contains(term.field()) }
  }

  def getTerms(query: Query): Set[Term] = {
    query match {
      case q: TermQuery => fromTermQuery(q)
      case q: PhraseQuery => fromPhraseQuery(q)
      case q: BooleanQuery => fromBooleanQuery(q)
      case q: ConditionalQuery => fromConditionalQuery(q)
      case q: ProximityQuery => fromProximityQuery(q)
      case q: SemanticVectorQuery => fromSemanticVectorQuery(q)
      case q: Query => fromOtherQuery(q)
      case null => Set.empty[Term]
    }
  }

  private def fromTermQuery(query: TermQuery) = Set(query.getTerm)
  private def fromPhraseQuery(query: PhraseQuery) = query.getTerms().toSet
  private def fromBooleanQuery(query: BooleanQuery) = {
    query.getClauses.foldLeft(Set.empty[Term]){ (s, c) => if (!c.isProhibited) s ++ getTerms(c.getQuery) else s }
  }
  private def fromConditionalQuery(query: ConditionalQuery) = getTerms(query.source) ++ getTerms(query.condition)
  private def fromProximityQuery(query: ProximityQuery) = query.terms.toSet
  private def fromSemanticVectorQuery(query: SemanticVectorQuery) = query.terms
  private def fromOtherQuery(query: Query) = {
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

  def getTermSeq(fieldName: String, query: Query): Seq[Term] = {
    query match {
      case q: TermQuery => seqFromTermQuery(fieldName, q)
      case q: PhraseQuery => seqFromPhraseQuery(fieldName, q)
      case q: BooleanQuery => seqFromBooleanQuery(fieldName, q)
      case _ => Seq.empty[Term] // ignore all other types of queries
    }
  }

  private def seqFromTermQuery(fieldName: String, query: TermQuery) = {
    val term = query.getTerm
    if (term.field() == fieldName) Seq(term) else Seq.empty[Term]
  }
  private def seqFromPhraseQuery(fieldName: String, query: PhraseQuery) = {
    val terms = query.getTerms()
    terms.filter{ _.field() == fieldName }.toSeq
  }
  private def seqFromBooleanQuery(fieldName: String, query: BooleanQuery) = {
    query.getClauses.foldLeft(Seq.empty[Term]){ (s, c) => if (!c.isProhibited) s ++ getTermSeq(fieldName, c.getQuery) else s }
  }

  def emptyScorer(weight: Weight) = new Scorer(weight) with Coordinator {
    override def score(): Float = 0.0f
    override def docID() = NO_MORE_DOCS
    override def nextDoc(): Int = NO_MORE_DOCS
    override def advance(target: Int): Int = NO_MORE_DOCS
    override def freq() = 0
  }

  def toScorerWithCoordinator(scorer: Scorer) = new Scorer(null.asInstanceOf[Weight]) with Coordinator {
    override def score() = scorer.score()
    override def docID() = scorer.docID()
    override def nextDoc() = scorer.nextDoc()
    override def advance(target: Int) = scorer.advance(target)
    override def freq() = scorer.freq()
  }

  def copy(query: TermQuery, field: String): Query = {
    if (query == null) null
    else {
      val term = new Term(field, query.getTerm().text())
      new TermQuery(term)
    }
  }

  def copy(query: PhraseQuery, field: String): Query = {
    if (query == null) null
    else {
      val newQuery = new PhraseQuery()
      val positions = query.getPositions()
      val terms = query.getTerms()
      val newTerms = terms.zip(positions).map{ case (t, p) => newQuery.add(new Term(field, t.text()), p) }
      newQuery
    }
  }

  def termDocsEnum(context: AtomicReaderContext, term: Term, acceptDocs: Bits): DocsEnum = {
    val fields = context.reader.fields()
    if (fields != null) {
      val terms = fields.terms(term.field())
      if (terms != null) {
        val termsEnum = terms.iterator(null)
        if (termsEnum.seekExact(term.bytes(), true)) {
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
        if (termsEnum.seekExact(term.bytes(), true)) {
          return termsEnum.docsAndPositions(acceptDocs, null)
        }
      }
    }
    return null
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
  }

  def emptyDocIdSetIterator: DocIdSetIterator = EmptyDocsAndPositionsEnum
  def emptyDocsEnum: DocsEnum = EmptyDocsAndPositionsEnum
  def emptyDocsAndPositionsEnum: DocsAndPositionsEnum = EmptyDocsAndPositionsEnum
}
