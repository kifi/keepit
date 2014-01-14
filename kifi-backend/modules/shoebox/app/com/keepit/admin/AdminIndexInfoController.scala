package com.keepit.controllers.admin

import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.service.ServiceType
import com.keepit.model.NormalizedURIStates._
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import play.api.libs.json.{JsNumber, JsObject}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject
import scala.concurrent.Await
import scala.concurrent.duration._

class AdminIndexInfoController @Inject()(
    actionAuthenticator: ActionAuthenticator,
    adminClusterController: AdminClusterController,
    searchClient: SearchServiceClient
  ) extends AdminController(actionAuthenticator) {

  def all = AdminHtmlAction { implicit request =>
    val infoFutures = searchClient.indexInfoList()
    val clusterMemberInfos = adminClusterController.clustersInfo.filter(_.serviceType == ServiceType.SEARCH).map{ i => (i.zkid, i) }.toMap

    val infos = infoFutures.flatMap{ future =>
      val (serviceInstance, indexInfos) = Await.result(future, 10 seconds)
      indexInfos.map{ info => (clusterMemberInfos.get(serviceInstance.id), info) }
    }
    Ok(views.html.admin.indexer(infos))
  }
}

