package com.keepit.controllers.admin

import com.keepit.search.SearchServiceClient
import com.keepit.common.controller.{ActionAuthenticator, AdminController}
import com.google.inject.Inject
import scala.concurrent.Await
import scala.concurrent.duration._

class AdminSpellCorrectorController @Inject() (
  searchClient: SearchServiceClient,
  actionAuthenticator: ActionAuthenticator
) extends AdminController(actionAuthenticator) {
  def suggest(input: String, boostScore: Boolean = true) = AdminHtmlAction { request =>
    val t1 = System.currentTimeMillis
    val suggest = Await.result(searchClient.correctSpelling(input, boostScore), 5 seconds)
    val t2 = System.currentTimeMillis
    Ok(s"time elpased: ${(t2 - t1)/1000.0} seconds\ninput: ${input}, suggestion: \n${suggest}")
  }
}