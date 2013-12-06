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
    val scores = s.leaveOneOut(queryText, stem, useSketch).toArray.sortBy(-_._2)
    val rv = scores.foldLeft(Map.empty[String, Float]){ case (m , (subTerms, score)) => m + (subTerms.map{_.text}.mkString(" ") -> score)}
    Ok(Json.toJson(rv))
  }
}
