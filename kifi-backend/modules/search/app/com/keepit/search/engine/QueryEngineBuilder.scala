package com.keepit.search.engine

import com.keepit.search.engine.query._
import com.keepit.search.query.{ FixedScoreQuery, HomePageQuery, ProximityQuery }
import org.apache.lucene.search.Query
import org.apache.lucene.search.BooleanClause.Occur._
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

object QueryEngineBuilder {
  val tieBreakerMultiplier = 1.5f

  class FilterQuery(subQuery: Query) extends FixedScoreQuery(subQuery)
}

class QueryEngineBuilder(coreQuery: Query) {
  import QueryEngineBuilder._

  private[this] val _tieBreakerMultiplier = tieBreakerMultiplier
  private[this] val _core = buildExpr(coreQuery) // (query, expr, size, recencyOnly)
  private[this] var _boosters: List[(Query, Float)] = Nil
  private[this] lazy val _final = buildFinal()
  private[this] var built = false

  def addBoosterQuery(boosterQuery: Query, boostStrength: Float): QueryEngineBuilder = {
    if (built) throw new IllegalStateException("cannot modify the engine builder once an engine is built")
    _boosters = (boosterQuery, boostStrength) :: _boosters
    this
  }

  def addFilterQuery(filter: Query): QueryEngineBuilder = {
    addBoosterQuery(new FilterQuery(filter), 1.0f)
  }

  def build(): QueryEngine = {
    new QueryEngine(_final._1, _final._2, _final._3, _core._3, _core._4)
  }

  def getQueryLabels(): Array[String] = buildLabels()

  private[this] def buildFinal(): (ScoreExpr, Query, Int) = {
    built = true
    val (query, expr, totalSize, noClickBoostNoSharingBoost) = _boosters.foldLeft(_core) {
      case ((query, expr, size, recencyOnly), (booster, boostStrength)) =>
        val boosterExpr = MaxExpr(size)
        (new KBoostQuery(query, booster, boostStrength), BoostExpr(expr, boosterExpr, boostStrength), size + 1, recencyOnly)
    }
    (expr, query, totalSize)
  }

  private[this] def buildExpr(query: Query): (Query, ScoreExpr, Int, Boolean) = {
    query match {
      case booleanQuery: KBooleanQuery =>
        val clauses = booleanQuery.clauses

        if (clauses.size == 1 && clauses.head.getOccur != MUST_NOT) return buildExpr(clauses.head.getQuery)

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

        (coreQuery, expr, exprIndex, false)

      case textQuery: KTextQuery =>
        (coreQuery, MaxWithTieBreakerExpr(0, _tieBreakerMultiplier), 1, false)

      case filterQuery: KFilterQuery =>
        // this is a filter only query, use FixedScoreQuery and MaxExpr, and disable click boost and sharing boost
        // so that the recency boosting takes over ranking
        // this is a special requirement for "tag:" only query on kifi.com
        (new KWrapperQuery(new FixedScoreQuery(filterQuery.subQuery)), MaxExpr(0), 1, true)

      case q =>
        (new KWrapperQuery(q), MaxWithTieBreakerExpr(0, _tieBreakerMultiplier), 1, false)
    }
  }

  private[this] def buildLabels(): Array[String] = {
    built = true

    val labels = new ArrayBuffer[String]

    coreQuery match {
      case booleanQuery: KBooleanQuery =>
        booleanQuery.clauses.foreach { clause =>
          val label = {
            clause.getOccur match {
              case MUST_NOT => s"${clause.getQuery.asInstanceOf[KTextQuery].label} (-)"
              case occur =>
                clause.getQuery match {
                  case q: KFilterQuery => q.label
                  case q: KTextQuery => if (occur == MUST) s"${q.label} (+)" else q.label
                  case q => q.toString
                }
            }
          }
          labels += label
        }

      case textQuery: KTextQuery => labels += textQuery.label

      case filterQuery: KFilterQuery => labels += filterQuery.label

      case q => labels += q.toString
    }

    _boosters.foreach {
      case (q, _) =>
        labels += {
          q match {
            case _: ProximityQuery => "proximity boost"
            case _: HomePageQuery => "home page boost"
            case f: FilterQuery => s"filter(${f.subQuery.toString})"
            case _ => s"boost(${q.toString})"
          }
        }
    }

    labels.toArray
  }
}
