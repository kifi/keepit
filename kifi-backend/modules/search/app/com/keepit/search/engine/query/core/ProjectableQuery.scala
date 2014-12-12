package com.keepit.search.engine.query.core

import org.apache.lucene.search._

import scala.collection.JavaConversions._

trait ProjectableQuery {
  def project(fields: Set[String]): Query
  def project(query: Query, fields: Set[String]): Query = QueryProjector.project(query, fields)
}

object QueryProjector {

  def projectTermQuery(query: TermQuery, fields: Set[String]): Query = {
    if (query == null) null
    else if (fields.contains(query.getTerm.field)) query
    else null
  }

  def projectPhraseQuery(query: PhraseQuery, fields: Set[String]): Query = {
    if (query == null) null
    else if (query.getTerms().forall(term => fields.contains(term.field))) query
    else null
  }

  def projectBooleanQuery(query: BooleanQuery, fields: Set[String]): Query = {
    val projectedQuery = new BooleanQuery()
    query.getClauses.foreach { c =>
      val q = project(c.getQuery, fields)
      if (q != null) projectedQuery.add(q, c.getOccur)
    }
    projectedQuery.setBoost(query.getBoost())
    projectedQuery
  }

  def projectDisjunctionMaxQuery(query: DisjunctionMaxQuery, fields: Set[String]): Query = {
    val projectedQuery = new DisjunctionMaxQuery(query.getTieBreakerMultiplier)
    query.getDisjuncts.foreach { subq =>
      val q = project(subq, fields)
      if (q != null) projectedQuery.add(q)
    }
    projectedQuery.setBoost(query.getBoost())
    projectedQuery
  }

  def project(query: Query, fields: Set[String]): Query = {
    query match {
      case null => null
      case q: ProjectableQuery => q.project(fields) // KTextQuery, KBooleanQuery, KFilterQuery, KBoostQuery
      case q: TermQuery => projectTermQuery(q, fields)
      case q: PhraseQuery => projectPhraseQuery(q, fields)
      case q: BooleanQuery => projectBooleanQuery(q, fields)
      case q: DisjunctionMaxQuery => projectDisjunctionMaxQuery(q, fields)
      case q => throw new Exception(s"failed to project query: ${q.toString}")
    }
  }
}
