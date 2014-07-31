package com.keepit.search.engine

import com.keepit.search.engine.query._
import com.keepit.search.engine.result.ResultCollector
import org.apache.lucene.search.Query
import org.apache.lucene.search.BooleanClause.Occur._
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

class QueryEngineBuilder(baseQuery: Query) {

  private[this] var _boosters: List[(Query, Float)] = Nil
  private[this] var _exprIndex: Int = 0

  def addBoosterQuery(booster: Query, boostStrength: Float): QueryEngineBuilder = {
    _boosters = (booster, boostStrength) :: _boosters
    this
  }

  def build(): QueryEngine = {
    _exprIndex = 0
    val (query, expr) = _boosters.foldLeft(buildExpr(baseQuery)) {
      case ((query, expr), (booster, boostStrength)) =>
        val boosterExpr = MaxExpr(_exprIndex)
        _exprIndex += 1
        (new KBoostQuery(query, booster, boostStrength), BoostExpr(expr, boosterExpr, boostStrength))
    }
    new QueryEngine(expr, query, _exprIndex)
  }

  private[this] def buildExpr(query: Query): (Query, ScoreExpr) = {
    query match {
      case booleanQuery: KBooleanQuery =>
        val clauses = booleanQuery.clauses
        val required = new ArrayBuffer[ScoreExpr]()
        val optional = new ArrayBuffer[ScoreExpr]()
        val filterOut = new ArrayBuffer[ScoreExpr]()

        clauses.foreach { clause =>
          clause.getOccur match {
            case MUST_NOT => filterOut += MaxExpr(_exprIndex)
            case occur =>
              clause.getQuery match {
                case q: KFilterQuery => required += MaxExpr(_exprIndex)
                case _ => (if (occur == MUST) required else optional) += MaxWithTieBreakerExpr(_exprIndex, 0.5f)
              }
          }
          _exprIndex += 1
        }

        // put all together and build a score expression
        val expr = FilterOutExpr(
          expr = BooleanExpr(
            optional = DisjunctiveSumExpr(optional),
            required = ConjunctiveSumExpr(required)
          ),
          filter = ExistsExpr(filterOut)
        )

        (baseQuery, expr)

      case textQuery: KTextQuery =>
        (baseQuery, MaxWithTieBreakerExpr(0, 0.5f))

      case q =>
        (new KWrapperQuery(q), MaxWithTieBreakerExpr(0, 0.5f))
    }
  }
}
