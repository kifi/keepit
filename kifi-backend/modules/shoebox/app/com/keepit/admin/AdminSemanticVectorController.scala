package com.keepit.controllers.admin

import com.keepit.search.SearchServiceClient
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AdminController
import com.google.inject.Inject
import scala.concurrent.Await
import scala.concurrent.duration._

class AdminSemanticVectorController @Inject()(
  searchClient: SearchServiceClient,
  actionAuthenticator: ActionAuthenticator
) extends AdminController(actionAuthenticator){

  def leaveOneOut(queryText: String, stem: Boolean, useSketch: Boolean) = AdminHtmlAction{ implicit request =>
    val t1 = System.currentTimeMillis
    val scores = Await.result(searchClient.leaveOneOut(queryText, stem, useSketch), 5 seconds).toArray.sortBy(-_._2)
    val elapse = System.currentTimeMillis - t1
    Ok(s"time elapsed: ${elapse} millis.\nFull query: ${queryText} \n" + scores.map{ x => x._1 + " ---> " + x._2}.mkString("\n"))
  }

  def allSubsets(queryText: String, stem: Boolean, useSketch: Boolean) = AdminHtmlAction{ implicit request =>
    val t1 = System.currentTimeMillis
    val scores = Await.result(searchClient.allSubsets(queryText, stem, useSketch), 5 seconds).toArray.sortBy(-_._2)
    val elapse = System.currentTimeMillis - t1
    Ok(s"time elapsed: ${elapse} millis.\nFull query: ${queryText} \n" + scores.map{ x => x._1 + " ---> " + x._2}.mkString("\n"))
  }
}
