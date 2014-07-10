package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ AdminController, ActionAuthenticator }
import com.keepit.graph.GraphServiceClient
import views.html
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class GraphAdminController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    graphClient: GraphServiceClient) extends AdminController(actionAuthenticator) {

  def statistics() = AdminHtmlAction.authenticatedAsync { implicit request =>
    val futureStatistics = graphClient.getGraphStatistics()
    val futureUpdaterStates = graphClient.getGraphUpdaterStates()
    for {
      statistics <- futureStatistics
      updaterStates <- futureUpdaterStates
    } yield {
      val allInstances = (statistics.keySet ++ updaterStates.keySet).toSeq
      Ok(html.admin.graph.graphStatisticsView(allInstances, statistics, updaterStates))

    }
  }
}
