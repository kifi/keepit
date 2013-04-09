package com.keepit.search.query

import com.keepit.common.logging.Logging
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.Weight
import org.apache.lucene.search.ComplexExplanation
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.util.PriorityQueue
import org.apache.lucene.util.ToStringUtils
import java.util.{Set => JSet}
import java.lang.{Float => JFloat}
import scala.collection.JavaConversions._
import scala.math._
import org.apache.lucene.search.IndexSearcher

trait BoostQuery extends Query {
  val textQuery: Query
  val boosterQueries: Array[Query]
  var enableCoord: Boolean = false

  protected val name: String

  protected def recreate(rewrittenTextQuery: Query, rewrittenBoosterQueries: Array[Query]): Query

  override def rewrite(reader: IndexReader): Query = {
    val rewrittenTextQuery = textQuery.rewrite(reader)
    val rewrittenBoosterQueries = boosterQueries.map{ q => q.rewrite(reader) }

    val textQueryUnchanged = (rewrittenTextQuery eq textQuery)
    val boosterQueriesUnchanged = rewrittenBoosterQueries.zip(boosterQueries).forall{ case (r, q) => r eq q }

    if (textQueryUnchanged && boosterQueriesUnchanged) this
    else recreate(rewrittenTextQuery, rewrittenBoosterQueries)
  }

  override def extractTerms(out: JSet[Term]): Unit = {
    textQuery.extractTerms(out)
    boosterQueries.foreach(_.extractTerms(out))
  }

  override def toString(s: String) = {
    "%s(%s, %s, %s)".format(
      name,
      textQuery.toString(s),
      boosterQueries.map(_.toString(s)).mkString("(",",",")"),
      enableCoord)
  }

  override def equals(obj: Any): Boolean = obj match {
    case query: BoostQuery =>
      name == query.name &&
      textQuery == query.textQuery &&
      boosterQueries.length == query.boosterQueries.length &&
      (boosterQueries.length == 0 || boosterQueries.zip(query.boosterQueries).forall{ case (a, b) => a.equals(b) })
    case _ => false
  }

  override def hashCode(): Int = name.hashCode() + textQuery.hashCode() + boosterQueries.foldLeft(0){ (sum, q) => sum + q.hashCode() }
}

trait BoostWeight extends Weight {

  val query: BoostQuery
  val searcher: IndexSearcher

  protected val textWeight: Weight = query.textQuery.createWeight(searcher)
  protected val boosterWeights: Array[Weight] = query.boosterQueries.map(_.createWeight(searcher))

  override def getQuery() = query
  override def scoresDocsOutOfOrder() = false

  protected def queryNorm(sum: Float): Float = {
    var norm = searcher.getSimilarity.queryNorm(sum)
    if (norm == Float.PositiveInfinity || norm == Float.NaN) norm = 1.0f
    norm
  }
}