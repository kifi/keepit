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
  private[this] lazy val _final = buildFinal()
  private[this] var built = false
  private[this] var noClickBoostNoSharingBoost = false

  def addBoosterQuery(booster: Query, boostStrength: Float): QueryEngineBuilder = {
    if (built) throw new IllegalStateException("cannot modify the engine builder once an engine is built")
    _boosters = (booster, boostStrength) :: _boosters
    this
  }

  def addFilterQuery(filter: Query): QueryEngineBuilder = {
    addBoosterQuery(new FixedScoreQuery(filter), 1.0f)
  }

  def build(): QueryEngine = {
    new QueryEngine(_final._1, _final._2, _final._3, _core._3, noClickBoostNoSharingBoost)
  }

  private[this] def buildFinal(): (ScoreExpr, Query, Int) = {
    built = true
    val (query, expr, totalSize) = _boosters.foldLeft(_core) {
      case ((query, expr, size), (booster, boostStrength)) =>
        val boosterExpr = MaxExpr(size)
        (new KBoostQuery(query, booster, boostStrength), BoostExpr(expr, boosterExpr, boostStrength), size + 1)
    }
    (expr, query, totalSize)
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

      case tagQuery: KFilterQuery =>
        // this is a filter only query, use FixedScoreQuery and MaxExpr, and disable click boost and sharing boost
        // so that the recency boosting takes over ranking
        // this is a special requirement for "tag:" only query on kifi.com
        noClickBoostNoSharingBoost = true
        (new KWrapperQuery(new FixedScoreQuery(tagQuery.subQuery)), MaxExpr(0), 1)

      case q =>
        (new KWrapperQuery(q), MaxWithTieBreakerExpr(0, _tieBreakerMultiplier), 1)
    }
  }
}
