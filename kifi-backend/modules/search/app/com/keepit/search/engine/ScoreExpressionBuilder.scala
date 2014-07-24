package com.keepit.search.engine

import com.keepit.search.engine.query.KBooleanQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.BooleanClause.Occur._
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

class ScoreExpressionBuilder(query: Query) {

  private[this] var expression: ScoreExpression = initExpr(query)
  private[this] val subqueries = new ArrayBuffer[Query]()

  def getScoreExpression() = expression

  def getSubQueries() = subqueries.toArray

  private[this] def initExpr(query: Query): ScoreExpression = {
    query match {
      case booleanQuery: KBooleanQuery =>
        val clauses = booleanQuery.clauses
        val required = new ArrayBuffer[ScoreExpression]()
        val prohibited = new ArrayBuffer[ScoreExpression]()
        val optional = new ArrayBuffer[ScoreExpression]()
        clauses.zipWithIndex.map {
          case (clause, i) =>
            subqueries += clause.getQuery
            clause.getOccur match {
              case MUST => required += new MaxWithTieBreakerExpr(i, 0.5f)
              case MUST_NOT => prohibited += new MaxExpr(i)
              case _ => optional += new MaxWithTieBreakerExpr(i, 0.5f)
            }
        }

        val requiredExpr = if (required.size > 0) new ConjunctiveSumExpr(required.toArray) else null
        val prohibitedExpr = if (prohibited.size > 0) new DisjunctiveSumExpr(prohibited.toArray) else null
        val optionalExpr = if (optional.size > 0) new DisjunctiveSumExpr(optional.toArray) else null

        if (requiredExpr == null && optionalExpr == null) {
          subqueries.clear()
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

      case _ =>
        subqueries += query
        new MaxWithTieBreakerExpr(0, 0.5f)
    }
  }

  def addBooster(booster: Query, boostStrength: Float): Unit = {
    val index = subqueries.size
    subqueries += query

    val boosterExpr = new MaxExpr(index)
    expression = new BoostExpr(expression, boosterExpr, boostStrength)
  }
}

