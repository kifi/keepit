package com.keepit.controllers.admin

import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.zookeeper.{ServiceDiscovery, ServiceInstance, ServiceCluster}
import com.keepit.common.service.{ServiceType, ServiceStatus}
import com.keepit.common.amazon.{AmazonInstanceInfo}
import com.google.inject.{Inject, Singleton}
import views.html




case class ClusterMemberInfo(serviceType: ServiceType, zkid: Long, isLeader: Boolean, instanceInfo: AmazonInstanceInfo, state: ServiceStatus, capabilities: List[String])



@Singleton
class AdminClusterController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    serviceDiscovery: ServiceDiscovery) extends AdminController(actionAuthenticator) {

    val serviceTypes : List[ServiceType] =  ServiceType.SEARCH :: ServiceType.SHOEBOX :: Nil

    def clustersView = AdminHtmlAction { implicit request =>
        
        var clustersInfo : Seq[ClusterMemberInfo] = serviceTypes.flatMap{ serviceType =>
            val serviceCluster = serviceDiscovery.serviceCluster(serviceType)
            serviceCluster.allServices.map { serviceInstance =>
                var isLeader = serviceCluster.leader.map(_==serviceInstance).getOrElse(false)
                var testCapabilities = if (serviceType==ServiceType.SEARCH) List("Search", "Find") else List("packaging footwear", "email") //this is just for UI testing and will be removed again soon.
                ClusterMemberInfo(serviceType, serviceInstance.id, isLeader, serviceInstance.instanceInfo, ServiceStatus.UP, testCapabilities)
            }

        }

        Ok(html.admin.adminClustersView(clustersInfo))
    }


}
