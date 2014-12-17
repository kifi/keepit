package com.keepit.controllers.admin

import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.service.ServiceType
import com.keepit.search.SearchServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject
import scala.concurrent.Future
import com.keepit.search.index.IndexInfo

class AdminIndexInfoController @Inject() (
    val userActionsHelper: UserActionsHelper,
    adminClusterController: AdminClusterController,
    searchClient: SearchServiceClient) extends AdminUserActions {

  def all = AdminUserPage.async { implicit request =>
    val clusterMemberInfos = adminClusterController.clustersInfo.filter(_.serviceType == ServiceType.SEARCH).map { i => (i.zkid, i) }.toMap

    val futureInstanceInfos = searchClient.indexInfoList.map { futureInstanceInfo =>
      futureInstanceInfo.map {
        case (serviceInstance, indexInfos) =>
          val indexInfo = indexInfos.map { info => (clusterMemberInfos.get(serviceInstance.id), IndexInfo.toReadableIndexInfo(info)) }
          val totalArticleIndexSize = indexInfos.filter { _.name startsWith "ArticleIndex" }.flatMap { _.indexSize }.foldLeft(0L)(_ + _)
          val totalSize = indexInfos.flatMap { _.indexSize }.foldLeft(0L)(_ + _)
          val totalSizeInfo = (clusterMemberInfos.get(serviceInstance.id), IndexInfo.toReadableSize(totalArticleIndexSize), IndexInfo.toReadableSize(totalSize))
          (indexInfo, totalSizeInfo)
      }
    }

    Future.sequence(futureInstanceInfos).map {
      case instanceInfos =>
        val (indexInfos, totalSizeInfos) = instanceInfos.unzip
        Ok(views.html.admin.indexer(indexInfos.flatten, totalSizeInfos))
    }
  }

  def viewIndexGrowth = AdminUserPage { implicit request =>
    Ok(views.html.admin.indexGrowth())
  }
}

