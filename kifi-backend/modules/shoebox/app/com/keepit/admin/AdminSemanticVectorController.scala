package com.keepit.controllers.admin

import com.keepit.search.SearchServiceClient
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AdminController
import com.google.inject.Inject
import scala.concurrent.Await
import scala.concurrent.duration._
import views.html
import play.api.libs.json._


class AdminSemanticVectorController @Inject()(
  searchClient: SearchServiceClient,
  actionAuthenticator: ActionAuthenticator
) extends AdminController(actionAuthenticator){

//  def leaveOneOut(queryText: String, stem: Boolean, useSketch: Boolean) = AdminHtmlAction{ implicit request =>
//    val t1 = System.currentTimeMillis
//    val scores = Await.result(searchClient.leaveOneOut(queryText, stem, useSketch), 5 seconds).toArray.sortBy(-_._2)
//    val elapse = System.currentTimeMillis - t1
//    Ok(s"time elapsed: ${elapse} millis.\nFull query: ${queryText} \n" + scores.map{ x => x._1 + " ---> " + x._2}.mkString("\n"))
//  }
//
//  def allSubsets(queryText: String, stem: Boolean, useSketch: Boolean) = AdminHtmlAction{ implicit request =>
//    val t1 = System.currentTimeMillis
//    val scores = Await.result(searchClient.allSubsets(queryText, stem, useSketch), 5 seconds).toArray.sortBy(-_._2)
//    val elapse = System.currentTimeMillis - t1
//    Ok(s"time elapsed: ${elapse} millis.\nFull query: ${queryText} \n" + scores.map{ x => x._1 + " ---> " + x._2}.mkString("\n"))
//  }

  def analysis() = AdminHtmlAction { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val query = body.get("query").get
    val level = body.get("subsetLevel").get
    val stem = body.get("stem").get.toBoolean
    val useSketch = body.get("sketch").get.toBoolean

    val t1 = System.currentTimeMillis
    val scores = level match {
      case "leaveOneOut" => Await.result(searchClient.leaveOneOut(query, stem, useSketch), 5 seconds).toArray.sortBy(-_._2)
      case "allSubsets" => Await.result(searchClient.allSubsets(query, stem, useSketch), 5 seconds).toArray.sortBy(-_._2)
    }
    val elapse = System.currentTimeMillis - t1
    val report = s"time elapsed: ${elapse} millis.\nFull query: ${query} \n" + scores.map{ x => x._1 + " ---> " + x._2}.mkString("\n")
    Ok(report.replaceAll("\n","\n<br>"))
  }

  def similarity() = AdminHtmlAction { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val (q1, q2) = (body.get("query1").get, body.get("query2").get)
    val stem = body.get("stem").get.toBoolean
    val score = Await.result(searchClient.semanticSimilarity(q1, q2, stem), 5 seconds)
    Ok(s"similarity: ${score}")
  }

  def index() = AdminHtmlAction { implicit request =>
    Ok(html.admin.SemanticVector())
  }
}
