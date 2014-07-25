package com.keepit.search.engine

import com.keepit.search.engine.query.{ KWrapperQuery, KTextQuery, KBoostQuery, KBooleanQuery }
import com.keepit.search.query.{ MediaQuery, SiteQuery }
import org.apache.lucene.search.Query
import org.apache.lucene.search.BooleanClause.Occur._
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

class QueryEngineBuilder(query: Query) {

  private[this] var _query: Query = query
  private[this] var _expression: ScoreExpr = initExpr(query)
  private[this] var _index: Int = 0
  private[this] var _threshold: Float = 0.0f
  private[this] var _filter: Option[Query] = None
  private[this] var _collector: ResultCollector = null

  def build(): QueryEngine = {

    new QueryEngine(_expression, _query, _index, _threshold, _collector)
  }

  private[this] def initExpr(query: Query): ScoreExpr = {
    query match {
      case booleanQuery: KBooleanQuery =>
        val clauses = booleanQuery.clauses
        val required = new ArrayBuffer[ScoreExpr]()
        val optional = new ArrayBuffer[ScoreExpr]()
        val filter = new ArrayBuffer[ScoreExpr]()
        val filterOut = new ArrayBuffer[ScoreExpr]()

        clauses.foreach { clause =>
          clause.getOccur match {
            case MUST_NOT => filterOut += MaxExpr(_index)
            case occur =>
              clause.getQuery match {
                case q: SiteQuery => filter += MaxExpr(_index)
                case q: MediaQuery => filter += MaxExpr(_index)
                case _ => (if (occur == MUST) required else optional) += MaxWithTieBreakerExpr(_index, 0.5f)
              }
          }
          _index += 1
        }

        FilterExpr(
          expr = FilterOutExpr(
            expr = BooleanExpr(
              optional = DisjunctiveSumExpr(optional),
              required = ConjunctiveSumExpr(required)
            ),
            filter = ExistsExpr(filterOut)
          ),
          filter = ForAllExpr(filter)
        )

      case textQuery: KTextQuery =>
        MaxWithTieBreakerExpr(0, 0.5f)

      case q =>
        _query = new KWrapperQuery(q)
        MaxWithTieBreakerExpr(0, 0.5f)
    }
  }

  def addBooster(booster: Query, boostStrength: Float): Unit = {
    _query = new KBoostQuery(_query, booster, boostStrength)
    val boosterExpr = MaxExpr(_index)
    _index += 1
    _expression = BoostExpr(_expression, boosterExpr, boostStrength)
  }

  def setFilter(filter: Query): Unit = {
    _filter = Some(filter)
  }

  def setPercentMatchThreshold(threshold: Float): Unit = {
    _threshold = threshold
  }

  def setResultCollector(collector: ResultCollector): Unit = {
    _collector = collector
  }
}

