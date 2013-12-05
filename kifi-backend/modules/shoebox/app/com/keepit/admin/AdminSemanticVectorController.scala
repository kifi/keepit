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

  def leaveOneOut(queryText: String, stem: Boolean) = AdminHtmlAction{ implicit request =>
    val scores = Await.result(searchClient.leaveOneOut(queryText, stem), 5 seconds)
    Ok(s"Full query: ${queryText} \n" + scores.mkString("\n"))
  }
}
