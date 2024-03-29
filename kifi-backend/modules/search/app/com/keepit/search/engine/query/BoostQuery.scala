package com.keepit.search.engine.query

import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.Weight
import java.util.{ Set => JSet }

import com.keepit.search.index.Searcher

trait BoostQuery extends Query {
  val textQuery: Query
  val boosterQuery: Query

  protected val name: String

  protected def recreate(rewrittenTextQuery: Query, rewrittenBoosterQuery: Query): Query

  override def rewrite(reader: IndexReader): Query = {
    val rewrittenTextQuery = textQuery.rewrite(reader)
    val rewrittenBoosterQuery = boosterQuery.rewrite(reader)

    if ((rewrittenTextQuery eq textQuery) && (rewrittenBoosterQuery eq boosterQuery)) this
    else recreate(rewrittenTextQuery, rewrittenBoosterQuery)
  }

  override def extractTerms(out: JSet[Term]): Unit = {
    textQuery.extractTerms(out)
    boosterQuery.extractTerms(out)
  }

  override def toString(s: String) = {
    "%s(%s, %s)".format(
      name,
      textQuery.toString(s),
      boosterQuery.toString(s))
  }

  override def equals(obj: Any): Boolean = obj match {
    case query: BoostQuery =>
      name == query.name &&
        textQuery == query.textQuery &&
        boosterQuery == query.boosterQuery
    case _ => false
  }

  override def hashCode(): Int = name.hashCode() + textQuery.hashCode() + boosterQuery.hashCode()
}

abstract class BoostWeight(query: BoostQuery, val searcher: IndexSearcher, needsScores: Boolean) extends Weight(query) {

  protected val textWeight: Weight = query.textQuery.createWeight(searcher, needsScores)
  protected val boosterWeight: Weight = query.boosterQuery.createWeight(searcher, needsScores)

  protected def queryNorm(sum: Float): Float = {
    var norm = searcher.getSimilarity.queryNorm(sum)
    if (norm == Float.PositiveInfinity || norm == Float.NaN) norm = 1.0f
    norm
  }
}