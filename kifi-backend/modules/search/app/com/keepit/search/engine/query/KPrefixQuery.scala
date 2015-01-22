package com.keepit.search.engine.query

import java.nio.charset.StandardCharsets

import com.keepit.common.logging.Logging
import com.keepit.search.engine.query.core.{ NullQuery, KWeight, KBooleanQuery, ProjectableQuery }
import com.keepit.search.index.Searcher
import com.keepit.typeahead.{ PrefixFilter, PrefixMatching }
import org.apache.lucene.index.{ BinaryDocValues, Term, AtomicReaderContext }
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.{ Scorer, Query, Weight, IndexSearcher, TermQuery }
import org.apache.lucene.util.Bits
import java.util.{ Set => JSet }

import scala.collection.mutable.ArrayBuffer

object KPrefixQuery {
  def get(prefixField: String, nameValueField: String, queryText: String): Option[KPrefixQuery] = {
    val terms = PrefixFilter.tokenize(queryText)
    if (terms.isEmpty) None else Some(new KPrefixQuery(prefixField, nameValueField, terms))
  }
}

class KPrefixQuery(val prefixField: String, val nameValueField: String, val terms: Array[String]) extends Query with ProjectableQuery {

  protected val name = "KPrefix"

  override def toString(s: String) = s"KPrefixQuery($prefixField-$nameValueField: ${terms.mkString(" & ")})"

  override def createWeight(searcher: IndexSearcher): Weight = new KPrefixWeight(this, searcher)

  override def extractTerms(out: JSet[Term]): Unit = terms.foreach(termString => out.add(new Term(prefixField, termString)))

  def project(fields: Set[String]): Query = if (fields.contains(prefixField)) this else new NullQuery() // NullQuery creates a NullWeight that still counts against the coreSize expected by QueryEngine

}

class KPrefixWeight(val query: KPrefixQuery, val searcher: IndexSearcher) extends Weight with KWeight with Logging {

  val booleanWeight = {
    val maxPrefixLength = searcher match {
      case kSearcher: Searcher => kSearcher.maxPrefixLength
      case _ => Int.MaxValue
    }
    val booleanQuery = new KBooleanQuery
    query.terms.foreach { token =>
      val termQuery = new TermQuery(new Term(query.prefixField, token.take(maxPrefixLength)))
      booleanQuery.add(termQuery, Occur.MUST)
    }
    booleanQuery.createWeight(searcher)
  }

  def getQuery(): KPrefixQuery = query

  def getValueForNormalization() = booleanWeight.getValueForNormalization

  def normalize(norm: Float, topLevelBoost: Float): Unit = booleanWeight.normalize(norm, topLevelBoost)

  def explain(context: AtomicReaderContext, doc: Int) = booleanWeight.explain(context, doc)

  override def scorer(context: AtomicReaderContext, acceptDocs: Bits): Scorer = {
    val nameDocsValues = context.reader.getBinaryDocValues(query.nameValueField)
    val booleanScorer = booleanWeight.scorer(context, acceptDocs)
    if (nameDocsValues == null || booleanScorer == null) null else new KPrefixScorer(this, booleanScorer, query.terms, nameDocsValues)
  }

  override def getWeights(out: ArrayBuffer[(Weight, Float)]): Unit = {
    out += ((this, 1.0f))
  }
}

class KPrefixScorer(weight: KPrefixWeight, subScorer: Scorer, queryTerms: Array[String], nameDocValues: BinaryDocValues) extends Scorer(weight) {
  override def docID(): Int = subScorer.docID()

  override def nextDoc(): Int = subScorer.nextDoc()

  override def advance(target: Int): Int = subScorer.advance(target)

  private def getName(): String = {
    val ref = nameDocValues.get(docID())
    new String(ref.bytes, ref.offset, ref.length, StandardCharsets.UTF_8)
  }

  override def score(): Float = {
    val name = getName()
    val distance = PrefixMatching.distance(name, queryTerms)
    val boost = (PrefixMatching.maxDist - distance).toFloat / PrefixMatching.maxDist // todo(Léo): boost shorter names
    subScorer.score() * boost
  }

  override def freq(): Int = -1
  override def cost(): Long = subScorer.cost()
}
