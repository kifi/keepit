package com.keepit.search.engine

import com.keepit.search.engine.query._
import com.keepit.search.engine.result.ResultCollector
import org.apache.lucene.search.Query
import org.apache.lucene.search.BooleanClause.Occur._
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

class QueryEngineBuilder(query: Query, percentMatchThreshold: Float) {

  private[this] var _query: Query = query
  private[this] var _expr: ScoreExpr = buildExpr(query)
  private[this] var _index: Int = 0
  private[this] var _collector: ResultCollector[ScoreContext] = null

  def build(): QueryEngine = {

    new QueryEngine(_expr, _query, _index, _collector)
  }

  private[this] def buildExpr(query: Query): ScoreExpr = {
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
                case q: KFilterQuery => filter += MaxExpr(_index)
                case _ => (if (occur == MUST) required else optional) += MaxWithTieBreakerExpr(_index, 0.5f)
              }
          }
          _index += 1
        }

        // put all together and build a score expression
        FilterExpr(
          expr = FilterOutExpr(
            expr = PercentMatchExpr(
              expr = BooleanExpr(
                optional = DisjunctiveSumExpr(optional),
                required = ConjunctiveSumExpr(required)
              ),
              threshold = percentMatchThreshold
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
    _expr = BoostExpr(_expr, boosterExpr, boostStrength)
  }

  def setResultCollector(collector: ResultCollector[ScoreContext]): Unit = {
    _collector = collector
  }
}

