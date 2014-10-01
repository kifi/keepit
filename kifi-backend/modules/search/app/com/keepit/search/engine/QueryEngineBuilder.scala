package com.keepit.search.engine

import com.keepit.search.engine.query._
import com.keepit.search.query.FixedScoreQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.BooleanClause.Occur._
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

object QueryEngineBuilder {
  val tieBreakerMultiplier = 1.5f
}

class QueryEngineBuilder(coreQuery: Query) {

  private[this] val _tieBreakerMultiplier = QueryEngineBuilder.tieBreakerMultiplier
  private[this] var _boosters: List[(Query, Float)] = Nil
  private[this] val _core = buildExpr(coreQuery) // (query, expr, size)

  def addBoosterQuery(booster: Query, boostStrength: Float): QueryEngineBuilder = {
    _boosters = (booster, boostStrength) :: _boosters
    this
  }

  def addFilterQuery(filter: Query): QueryEngineBuilder = {
    addBoosterQuery(new FixedScoreQuery(filter), 1.0f)
  }

  def build(): QueryEngine = {
    val (query, expr, totalSize) = _boosters.foldLeft(_core) {
      case ((query, expr, size), (booster, boostStrength)) =>
        val boosterExpr = MaxExpr(size)
        (new KBoostQuery(query, booster, boostStrength), BoostExpr(expr, boosterExpr, boostStrength), size + 1)
    }
    new QueryEngine(expr, query, totalSize, _core._3)
  }

  private[this] def buildExpr(query: Query): (Query, ScoreExpr, Int) = {
    query match {
      case booleanQuery: KBooleanQuery =>
        val clauses = booleanQuery.clauses
        val required = new ArrayBuffer[ScoreExpr]()
        val optional = new ArrayBuffer[ScoreExpr]()
        val filterOut = new ArrayBuffer[ScoreExpr]()

        var exprIndex = 0
        clauses.foreach { clause =>
          clause.getOccur match {
            case MUST_NOT => filterOut += MaxExpr(exprIndex)
            case occur =>
              clause.getQuery match {
                case q: KFilterQuery => required += MaxExpr(exprIndex)
                case _ => (if (occur == MUST) required else optional) += MaxWithTieBreakerExpr(exprIndex, _tieBreakerMultiplier)
              }
          }
          exprIndex += 1
        }

        // put all together and build a score expression
        val expr = FilterOutExpr(
          expr = BooleanExpr(
            optional = DisjunctiveSumExpr(optional),
            required = ConjunctiveSumExpr(required)
          ),
          filter = ExistsExpr(filterOut)
        )

        (coreQuery, expr, exprIndex)

      case textQuery: KTextQuery =>
        (coreQuery, MaxWithTieBreakerExpr(0, _tieBreakerMultiplier), 1)

      case q =>
        (new KWrapperQuery(q), MaxWithTieBreakerExpr(0, _tieBreakerMultiplier), 1)
    }
  }
}
