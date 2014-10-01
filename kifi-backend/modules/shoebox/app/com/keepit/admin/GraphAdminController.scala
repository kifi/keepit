package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.graph.GraphServiceClient
import views.html
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class GraphAdminController @Inject() (
    val userActionsHelper: UserActionsHelper,
    graphClient: GraphServiceClient) extends AdminUserActions {

  def statistics() = AdminUserPage.async { implicit request =>
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
