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

class QueryEngineBuilder(userQuery: Query) {
  import QueryEngineBuilder._

  private[this] val _tieBreakerMultiplier = tieBreakerMultiplier
  private[this] val _expr = buildExpr(userQuery) // (expr, query, size, recencyOnly)
  private[this] var _filters: List[(Query, Float)] = Nil
  private[this] var _boosters: List[(Query, Float)] = Nil
  private[this] lazy val _core = buildCore() // (expr, query, size)
  private[this] lazy val _final = buildFinal()
  private[this] var built = false

  def addBoosterQuery(boosterQuery: Query, boostStrength: Float): QueryEngineBuilder = {
    if (built) throw new IllegalStateException("cannot modify the engine builder once an engine is built")
    _boosters = (boosterQuery, boostStrength) :: _boosters
    this
  }

  def addFilterQuery(filter: Query): QueryEngineBuilder = {
    if (built) throw new IllegalStateException("cannot modify the engine builder once an engine is built")
    _filters = (new FilterQuery(filter), 1.0f) :: _filters
    this
  }

  def build(): QueryEngine = {
    new QueryEngine(_final._1, _final._2, _final._3, _core._3, _expr._4)
  }

  def getQueryLabels(): Array[String] = buildLabels()

  private[this] def buildFinal(): (ScoreExpr, Query, Int) = {
    built = true
    val (expr, query, totalSize) = _boosters.foldLeft(_core) {
      case ((expr, query, size), (booster, boostStrength)) =>
        val boosterExpr = MaxExpr(size)
        (BoostExpr(expr, boosterExpr, boostStrength), new KBoostQuery(query, booster, boostStrength), size + 1)
    }
    (expr, query, totalSize)
  }

  private[this] def buildCore(): (ScoreExpr, Query, Int) = {
    built = true
    val (expr, query, totalSize, noClickBoostNoSharingBoost) = _filters.foldLeft(_expr) {
      case ((expr, query, size, recencyOnly), (booster, boostStrength)) =>
        val boosterExpr = MaxExpr(size)
        (BoostExpr(expr, boosterExpr, boostStrength), new KBoostQuery(query, booster, boostStrength), size + 1, recencyOnly)
    }
    (expr, query, totalSize)
  }

  private[this] def buildExpr(query: Query): (ScoreExpr, Query, Int, Boolean) = {
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

        (expr, userQuery, exprIndex, false)

      case textQuery: KTextQuery =>
        (MaxWithTieBreakerExpr(0, _tieBreakerMultiplier), userQuery, 1, false)

      case filterQuery: KFilterQuery =>
        // this is a filter only query, use FixedScoreQuery and MaxExpr, and disable click boost and sharing boost
        // so that the recency boosting takes over ranking
        // this is a special requirement for "tag:" only query on kifi.com
        (MaxExpr(0), new KWrapperQuery(new FixedScoreQuery(filterQuery.subQuery)), 1, true)

      case q =>
        (MaxWithTieBreakerExpr(0, _tieBreakerMultiplier), new KWrapperQuery(q), 1, false)
    }
  }

  private[this] def buildLabels(): Array[String] = {
    built = true

    val labels = new ArrayBuffer[String]

    userQuery match {
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

    def toLabel(q: Query): String = {
      q match {
        case _: KProximityQuery => "proximity boost"
        case _: HomePageQuery => "home page boost"
        case f: FilterQuery => s"filter(${f.subQuery.toString})"
        case _ => s"boost(${q.toString})"
      }
    }

    _filters.foreach { case (q, _) => labels += toLabel(q) }
    _boosters.foreach { case (q, _) => labels += toLabel(q) }

    labels.toArray
  }
}
