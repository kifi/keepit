package com.keepit.controllers.search

import com.keepit.search.index.ArticleIndexer
import com.google.inject.Inject
import com.keepit.common.controller.SearchServiceController
import com.keepit.search.query.SemanticContextAnalyzer
import com.keepit.search.index.DefaultAnalyzer
import org.apache.lucene.index.Term
import play.api.mvc.Action
import play.api.libs.json._

class SemanticVectorController @Inject()(articleIndexer: ArticleIndexer) extends SearchServiceController {
  val searcher = articleIndexer.getSearcher
  val analyzer = DefaultAnalyzer.defaultAnalyzer
  val stemAnalyzer = DefaultAnalyzer.forParsingWithStemmer

  // return: subQuery -> similarityScore
  def leaveOneOut(queryText: String, stem: Boolean, useSketch: Boolean) = Action { request =>
    val s = new SemanticContextAnalyzer(searcher, analyzer, stemAnalyzer)
    val scores = s.leaveOneOut(queryText, stem, useSketch)
    val rv = scores.foldLeft(Map.empty[String, Float]){ case (m , (subTerms, score)) => m + (subTerms.map{_.text}.mkString(" ") -> score)}
    Ok(Json.toJson(rv))
  }

  def allSubsets(queryText: String, stem: Boolean, useSketch: Boolean) = Action { request =>
    val s = new SemanticContextAnalyzer(searcher, analyzer, stemAnalyzer)
    val scores = s.allSubsets(queryText, stem, useSketch)
    val rv = scores.foldLeft(Map.empty[String, Float]){ case (m , (subTerms, score)) => m + (subTerms.map{_.text}.mkString(" ") -> score)}
    Ok(Json.toJson(rv))
  }

  def similarity(query1: String, query2: String, stem: Boolean) = Action { request =>
    val s = new SemanticContextAnalyzer(searcher, analyzer, stemAnalyzer)
    val score = s.similarity(query1, query2, stem)
    Ok(Json.toJson(score))
  }

  def visualizeSemanticVector() = Action(parse.json){ request =>
    val queries = Json.fromJson[Seq[String]](request.body).get
    val s = new SemanticContextAnalyzer(searcher, analyzer, stemAnalyzer)
    val rv = queries.map{ q => s.getSemanticVector(q).toBinary }
    Ok(Json.toJson(rv))
  }

  def semanticLoss(queryText: String) = Action { request =>
    val s = new SemanticContextAnalyzer(searcher, analyzer, stemAnalyzer)
    val scores = s.semanticLoss(queryText)
    Ok(Json.toJson(scores))
  }
}
