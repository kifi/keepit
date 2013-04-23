package com.keepit.search.query

import com.keepit.search.SemanticVector
import com.keepit.search.index.Searcher
import com.keepit.search.query.QueryUtil._
import org.apache.lucene.index.AtomicReader
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.index.DocsAndPositionsEnum
import org.apache.lucene.index.FilterAtomicReader.FilterDocsAndPositionsEnum
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.Weight
import org.apache.lucene.search.ComplexExplanation
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.similarities.Similarity
import org.apache.lucene.util.Bits
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.PriorityQueue
import org.apache.lucene.util.ToStringUtils
import java.util.{Set => JSet}
import java.lang.{Float => JFloat}
import scala.collection.JavaConversions._
import scala.math._

object SemanticVectorQuery {
  def apply(terms: Iterable[Term], fallbackField: String) = new SemanticVectorQuery(terms, fallbackField)
}

class SemanticVectorQuery(val terms: Iterable[Term], val fallbackField: String) extends Query {

  override def createWeight(searcher: IndexSearcher): Weight = {
    new SemanticVectorWeight(this, searcher.asInstanceOf[Searcher])
  }

  override def rewrite(reader: IndexReader): Query = this

  override def extractTerms(out: JSet[Term]): Unit = out.addAll(terms)

  override def toString(s: String) = {
    "semanticvector(%s|%s)%s".format(terms.mkString(","), fallbackField, ToStringUtils.boost(getBoost()))
  }

  override def equals(obj: Any): Boolean = obj match {
    case svq: SemanticVectorQuery => (terms == svq.terms && fallbackField == svq.fallbackField && getBoost() == svq.getBoost())
    case _ => false
  }

  override def hashCode(): Int = terms.hashCode() + fallbackField.hashCode() + JFloat.floatToRawIntBits(getBoost())
}

class SemanticVectorWeight(query: SemanticVectorQuery, searcher: Searcher) extends Weight {

  private[this] var value = 0.0f

  private[this] val (termList, idfSum) = {
    var sum = 0.0f
    val terms = query.terms.toList.map{ term =>
      val vector = searcher.getSemanticVector(term)
      val idf = searcher.idf(term)
      sum += idf
      (term, new Term(query.fallbackField, term.text), vector, idf)
    }
    (terms, sum)
  }

  override def getQuery() = query
  override def scoresDocsOutOfOrder() = false

  override def getValueForNormalization() = {
    val boost = query.getBoost()
    boost * boost
  }

  override def normalize(norm: Float, topLevelBoost: Float) {
    value = query.getBoost * norm * topLevelBoost / idfSum
  }

  override def explain(context: AtomicReaderContext, doc: Int) = {
    val reader = context.reader
    val sc = scorer(context, true, false, reader.getLiveDocs);
    val exists = (sc != null && sc.advance(doc) == doc);

    val result = new ComplexExplanation()
    if (exists) {
      result.setDescription("semantic vector (%s), sum of:".format(query.terms.mkString(",")))
      result.setValue(sc.score)
      result.setMatch(true)

      termList.map{ case (term, _, vector, idf) =>
        explainTerm(context, term, vector, idf * value, doc) match {
          case Some(detail) => result.addDetail(detail)
          case None =>
        }
      }
    } else {
      result.setDescription("semantic vector (%s), doesn't match id %d".format(query.terms.mkString(","), doc))
      result.setValue(0)
      result.setMatch(false)
    }
    result
  }

  private def explainTerm(context: AtomicReaderContext, term: Term, vector: SemanticVector, weight: Float, doc: Int): Option[Explanation] = {
    Option(getDocsAndPositionsEnum(context, term, new Term(query.fallbackField, term.text()), context.reader.getLiveDocs)).flatMap{ tp =>
      val dv = new DocAndVector(tp, vector, weight)
      dv.fetchDoc(doc)
      if (dv.doc == doc && weight > 0.0f) {
        val sc = dv.scoreAndNext()
        val expl = new ComplexExplanation()
        expl.setDescription("term(%s)".format(term.toString))
        expl.addDetail(new Explanation(sc/weight, "similarity"))
        expl.addDetail(new Explanation(weight, "boost"))
        expl.setValue(sc)
        Some(expl)
      } else {
        None
      }
    }
  }

  override def scorer(context: AtomicReaderContext, scoreDocsInOrder: Boolean, topScorer: Boolean, acceptDocs: Bits): Scorer = {
    val davs = termList.flatMap{ case (primaryTerm, secondaryTerm, vector, idf) =>
      Option(getDocsAndPositionsEnum(context, primaryTerm, secondaryTerm, acceptDocs)).map{ tp => new DocAndVector(tp, vector, idf * value) }
    }

    if (!davs.isEmpty) {
      new SemanticVectorScorer(this, davs)
    } else {
      QueryUtil.emptyScorer(this)
    }
  }

  private def getDocsAndPositionsEnum(context: AtomicReaderContext, primaryTerm: Term, secondaryTerm: Term, acceptDocs: Bits) = {
    val primary = termPositionsEnum(context, primaryTerm, acceptDocs)
    val defaultSV = searcher.getSemanticVector(primaryTerm) // use the query's sv as a default
    val secondary = getFallbackDocsAndPositionsEnum(context, secondaryTerm, defaultSV, acceptDocs)

    if (primary == null) secondary
    else if (secondary == null) primary
    else {
      new DocsAndPositionsEnumWithFallback(primary, secondary)
    }
  }

  private def getFallbackDocsAndPositionsEnum(context: AtomicReaderContext, term: Term, sv: SemanticVector, acceptDocs: Bits) = {
    val tp = termPositionsEnum(context, term, acceptDocs)
    if (tp != null) {
      val payload = new BytesRef(sv.bytes, 0, sv.bytes.length)
      new FilterDocsAndPositionsEnum(tp) {
        override def getPayload(): BytesRef = payload
      }
    } else null
  }
}

private[query] final class DocsAndPositionsEnumWithFallback(primary: DocsAndPositionsEnum, secondary: DocsAndPositionsEnum) extends DocsAndPositionsEnum {
  var doc = -1
  private var docPrimary = -1
  private var docSecondary = -1

  override def docID(): Int = doc

  override def nextDoc(): Int = {
    doc = doc + 1
    if (docPrimary < doc) docPrimary = primary.advance(doc)
    if (docSecondary < doc) docSecondary = secondary.advance(doc)
    doc = min(docPrimary, docSecondary)
    doc
  }

  override def advance(target: Int): Int = {
    doc = if (doc < target) target else doc + 1
    if (docPrimary <= target) docPrimary = primary.advance(doc)
    if (docSecondary <= target) docSecondary = secondary.advance(doc)
    doc = min(docPrimary, docSecondary)
    doc
  }

  private def tp: DocsAndPositionsEnum = if (doc == docPrimary) primary else secondary
  override def freq(): Int = tp.freq()
  override def nextPosition(): Int = tp.nextPosition()
  override def getPayload(): BytesRef = tp.getPayload()
  override def startOffset() = -1
  override def endOffset() = -1
}

private[query] final class DocAndVector(tp: DocsAndPositionsEnum, vector: SemanticVector, weight: Float) {
  var doc = -1

  private[this] var sv = new SemanticVector(new Array[Byte](SemanticVector.arraySize))

  def fetchDoc(target: Int) {
    doc = tp.advance(target)
  }

  def scoreAndNext(): Float = {
    val score = if (tp.freq() > 0) {
      tp.nextPosition()
      val payload = tp.getPayload()
      if (payload != null) {
        sv.set(payload.bytes, payload.offset, payload.length)
        vector.similarity(sv) * weight
      } else {
        0.0f
      }
    } else {
      0.0f
    }

    doc = tp.nextDoc()

    score
  }
}

class SemanticVectorScorer(weight: SemanticVectorWeight, davs: List[DocAndVector]) extends Scorer(weight) {
  private[this] var curDoc = -1
  private[this] var svScore = 0.0f
  private[this] var scoredDoc = -1

  private[this] val pq = new PriorityQueue[DocAndVector](davs.size) {
    override def lessThan(nodeA: DocAndVector, nodeB: DocAndVector) = (nodeA.doc < nodeB.doc)
  }
  davs.foreach{ dav => pq.insertWithOverflow(dav) }

  override def score(): Float = {
    val doc = curDoc
    if (scoredDoc != doc) {
      var top = pq.top
      var sum = 0.0f
      while (top.doc == doc) {
        sum += top.scoreAndNext()
        top = pq.updateTop()
      }
      if (sum > 0.0f) svScore = sum else svScore = Float.MinPositiveValue
      scoredDoc = doc
    }
    svScore
  }

  override def docID(): Int = curDoc

  override def nextDoc(): Int = advance(0)

  override def advance(target: Int): Int = {
    var top = pq.top
    val doc = if (target <= curDoc && curDoc < DocIdSetIterator.NO_MORE_DOCS) curDoc + 1 else target
    while (top.doc < doc) {
      top.fetchDoc(doc)
      top = pq.updateTop()
    }
    curDoc = top.doc
    curDoc
  }

  override def freq(): Int = 1
}

