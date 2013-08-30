package com.keepit.controllers.admin

import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.zookeeper.{ServiceDiscovery, ServiceInstance, ServiceCluster}
import com.keepit.common.service.{ServiceType, ServiceStatus, ServiceVersion}
import com.keepit.common.amazon.{AmazonInstanceInfo}
import com.keepit.common.routes.Common
import com.keepit.common.net.HttpClient
import com.google.inject.Inject
import views.html
import java.net.InetAddress

case class ClusterMemberInfo(serviceType: ServiceType, zkid: Long, isLeader: Boolean, instanceInfo: AmazonInstanceInfo, publicHostname:String, state: ServiceStatus, capabilities: List[String], version: ServiceVersion)

class AdminClusterController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    httpClient: HttpClient,
    serviceDiscovery: ServiceDiscovery) extends AdminController(actionAuthenticator) {

    val serviceTypes : List[ServiceType] =  ServiceType.SEARCH :: ServiceType.SHOEBOX :: ServiceType.ELIZA :: Nil

    def clustersView = AdminHtmlAction { implicit request =>
        
        var clustersInfo : Seq[ClusterMemberInfo] = serviceTypes.flatMap{ serviceType =>
            val serviceCluster = serviceDiscovery.serviceCluster(serviceType)
            serviceCluster.allMembers.map { serviceInstance =>
                var isLeader = serviceCluster.leader.map(_==serviceInstance).getOrElse(false)
                var testCapabilities = if (serviceType==ServiceType.SEARCH) List("Search", "Find") else List("packaging footwear", "email")
                val versionResp = httpClient.get("http://" + serviceInstance.instanceInfo.publicHostname + ":9000" + Common.internal.version().url)
                val publicHostName = InetAddress.getByName(serviceInstance.instanceInfo.publicIp.ip).getHostName()
                ClusterMemberInfo(serviceType, serviceInstance.id, isLeader, serviceInstance.instanceInfo, publicHostName, serviceInstance.remoteService.status, testCapabilities, ServiceVersion(versionResp.body))
            }
        }

        Ok(html.admin.adminClustersView(clustersInfo))
    }
}
