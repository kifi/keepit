package com.keepit.controllers.admin

import com.keepit.common.controller.{ AdminController, ActionAuthenticator }
import com.keepit.common.zookeeper._
import com.keepit.common.service.{ ServiceUri, ServiceType, ServiceStatus, ServiceVersion }
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.routes.Common
import com.keepit.common.net.HttpClient
import com.google.inject.{ Inject, Singleton }
import play.api.libs.json.{ JsNull, JsValue, Json }
import views.html
import java.net.InetAddress
import scala.collection.mutable.WeakHashMap
import com.keepit.common.core._

case class ClusterMemberInfo(serviceType: ServiceType, zkid: ServiceInstanceId, isLeader: Boolean, instanceInfo: AmazonInstanceInfo,
  localHostName: String, state: ServiceStatus, version: ServiceVersion, name: String)

class AdminClusterController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    serviceVersionMap: ServiceVersionMap,
    serviceDiscovery: ServiceDiscovery,
    zooKeeperClient: ZooKeeperClient) extends AdminController(actionAuthenticator) {

  val machineNames = Map[String, String](
    "50.18.183.73" -> "b01",
    "184.169.164.108" -> "b02",
    "50.18.123.43" -> "b04",
    "54.241.10.138" -> "b05",
    "184.169.163.57" -> "b06",
    "184.169.149.248" -> "b07",
    "184.169.206.118" -> "b08",
    "54.215.103.202" -> "b09",
    "54.215.113.116" -> "b10",
    "54.219.29.184" -> "b11"
  )

  def clustersInfo: Seq[ClusterMemberInfo] = ServiceType.inProduction.flatMap { serviceType =>
    val serviceCluster = serviceDiscovery.serviceCluster(serviceType)
    serviceCluster.allMembers.map { serviceInstance =>
      val isLeader = serviceCluster.leader.exists(_ == serviceInstance)
      val serviceVersion = serviceVersionMap(serviceInstance)
      val publicHostName = InetAddress.getByName(serviceInstance.instanceInfo.localIp.ip).getHostName
      val name = machineNames.get(serviceInstance.instanceInfo.publicIp.toString()).getOrElse("NA")
      ClusterMemberInfo(serviceType, serviceInstance.id, isLeader, serviceInstance.instanceInfo, publicHostName, serviceInstance.remoteService.status, serviceVersion, name)
    }
  }

  def zooKeeperData: JsValue = zooKeeperClient.session { session =>
    val tree = session.getSubtree("/fortytwo")
    def convertData(tree: ZooKeeperSubtree): JsValue = {
      val jsData: JsValue = tree.data.getOrElse(JsNull)
      Json.obj("path" -> tree.path, "data" -> jsData, "children" -> tree.children.map(child => convertData(child)))
    }
    convertData(tree)
  }

  def clustersView = AdminHtmlAction.authenticated { implicit request =>
    Ok(html.admin.adminClustersView(clustersInfo))
  }

  def zooKeeperInspector = AdminHtmlAction.authenticated { implicit request =>
    Ok(zooKeeperData)
  }
}

@Singleton
class ServiceVersionMap @Inject() (httpClient: HttpClient) {

  private[this] val weakMap = new WeakHashMap[ServiceInstance, ServiceVersion]

  def apply(serviceInstance: ServiceInstance): ServiceVersion = synchronized {
    weakMap.get(serviceInstance) match {
      case Some(version) => version
      case None =>
        val versionResp: String = try {
          httpClient.get(new ServiceUri(serviceInstance, "http", 9000, Common.internal.version().url), httpClient.ignoreFailure).body
        } catch {
          case _: Throwable => return ServiceVersion("00000000-0000-NANA-0000000")
        }
        try {
          ServiceVersion(versionResp) tap { version => weakMap += (serviceInstance -> version) }
        } catch {
          case t: Throwable => return ServiceVersion("00000000-0000-HUHH-0000000")
        }
    }
  }
}

