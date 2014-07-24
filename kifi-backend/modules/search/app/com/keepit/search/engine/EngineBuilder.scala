package com.keepit.search.engine

import com.keepit.search.engine.query.{ KWrapperQuery, KTextQuery, KBoostQuery, KBooleanQuery }
import org.apache.lucene.search.Query
import org.apache.lucene.search.BooleanClause.Occur._
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

class EngineBuilder(query: Query) {

  private[this] var _query: Query = query
  private[this] var _expression: ScoreExpression = initExpr(query)
  private[this] var _index: Int = 0
  private[this] var _threshold: Float = 0.0f

  def build(): Engine = new Engine(_expression, _query, _index, _threshold)

  private[this] def initExpr(query: Query): ScoreExpression = {
    query match {
      case booleanQuery: KBooleanQuery =>
        val clauses = booleanQuery.clauses
        val required = new ArrayBuffer[ScoreExpression]()
        val prohibited = new ArrayBuffer[ScoreExpression]()
        val optional = new ArrayBuffer[ScoreExpression]()
        clauses.map { clause =>
          clause.getOccur match {
            case MUST => required += new MaxWithTieBreakerExpr(_index, 0.5f)
            case MUST_NOT => prohibited += new MaxExpr(_index)
            case _ => optional += new MaxWithTieBreakerExpr(_index, 0.5f)
          }
          _index += 1
        }

        val requiredExpr = if (required.size > 0) new ConjunctiveSumExpr(required.toArray) else null
        val prohibitedExpr = if (prohibited.size > 0) new DisjunctiveSumExpr(prohibited.toArray) else null
        val optionalExpr = if (optional.size > 0) new DisjunctiveSumExpr(optional.toArray) else null

        if (requiredExpr == null && optionalExpr == null) {
          NullExpr
        } else {
          def expr = {
            if (requiredExpr != null) {
              if (optionalExpr != null) new BooleanExpr(optionalExpr, requiredExpr) else requiredExpr
            } else {
              optionalExpr
            }
          }

          if (prohibited.size == 0) expr else new BooleanNotExpr(expr, new ExistsExpr(prohibited.toArray))
        }

      case textQuery: KTextQuery =>
        new MaxWithTieBreakerExpr(0, 0.5f)

      case q =>
        _query = new KWrapperQuery(q)
        new MaxWithTieBreakerExpr(0, 0.5f)
    }
  }

  def addBooster(booster: Query, boostStrength: Float): Unit = {
    _query = new KBoostQuery(_query, booster, boostStrength)
    val boosterExpr = new MaxExpr(_index)
    _index += 1
    _expression = new BoostExpr(_expression, boosterExpr, boostStrength)
  }

  def setPercentMatchThreshold(threshold: Float): Unit = {
    _threshold = threshold
  }
}

