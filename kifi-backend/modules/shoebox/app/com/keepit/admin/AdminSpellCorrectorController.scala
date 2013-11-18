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
  def suggest(input: String) = AdminHtmlAction { request =>

    val suggest = Await.result(searchClient.correctSpelling(input), 5 seconds)
    Ok(s"input: ${input}, suggestion: \n${suggest}")
  }
}