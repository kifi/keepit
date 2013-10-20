package com.keepit.controllers.admin

import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.zookeeper.{ServiceDiscovery, ServiceInstance, ServiceCluster, ServiceInstanceId}
import com.keepit.common.service.{ServiceType, ServiceStatus, ServiceVersion}
import com.keepit.common.amazon.{AmazonInstanceInfo}
import com.keepit.common.routes.Common
import com.keepit.common.net.{HttpClient, DirectUrl}
import com.google.inject.Inject
import views.html
import java.net.InetAddress

case class ClusterMemberInfo(serviceType: ServiceType, zkid: ServiceInstanceId, isLeader: Boolean, instanceInfo: AmazonInstanceInfo,
        localHostName:String, state: ServiceStatus, capabilities: List[String], version: ServiceVersion, name: String)

class AdminClusterController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    httpClient: HttpClient,
    serviceDiscovery: ServiceDiscovery) extends AdminController(actionAuthenticator) {

    val serviceTypes : List[ServiceType] =  ServiceType.SEARCH :: ServiceType.SHOEBOX :: ServiceType.ELIZA :: ServiceType.HEIMDAL :: ServiceType.ABOOK :: Nil

    val machineNames = Map[String, String](
        "50.18.183.73"    -> "b01",
        "184.169.164.108" -> "b02",
        "50.18.123.43"    -> "b04",
        "54.241.10.138"   -> "b05",
        "184.169.163.57"  -> "b06",
        "184.169.149.248" -> "b07",
        "184.169.206.118" -> "b08",
        "54.215.103.202"  -> "b09",
        "54.215.113.116"  -> "b10",
        "54.219.29.184"   -> "b11"
    )

    def clustersView = AdminHtmlAction { implicit request =>

        var clustersInfo : Seq[ClusterMemberInfo] = serviceTypes.flatMap{ serviceType =>
            val serviceCluster = serviceDiscovery.serviceCluster(serviceType)
            serviceCluster.allMembers.map { serviceInstance =>
                var isLeader = serviceCluster.leader.map(_==serviceInstance).getOrElse(false)
                var testCapabilities = if (serviceType==ServiceType.SEARCH) List("Search", "Find") else List("packaging footwear", "email")
                val versionResp : String = try {
                    httpClient.get(DirectUrl("http://" + serviceInstance.instanceInfo.localHostname + ":9000" + Common.internal.version().url), httpClient.ignoreFailure).body
                } catch {
                    case _ => "NA"
                }
                val publicHostName = InetAddress.getByName(serviceInstance.instanceInfo.localIp.ip).getHostName()
                val name = machineNames.get(serviceInstance.instanceInfo.publicIp.toString).getOrElse("NA")
                ClusterMemberInfo(serviceType, serviceInstance.id, isLeader, serviceInstance.instanceInfo, publicHostName, serviceInstance.remoteService.status, testCapabilities, ServiceVersion(versionResp), name)
            }
        }

        Ok(html.admin.adminClustersView(clustersInfo))
    }
}
