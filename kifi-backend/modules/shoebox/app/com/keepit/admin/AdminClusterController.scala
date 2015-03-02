package com.keepit.controllers.admin

import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.zookeeper._
import com.keepit.common.service.{ ServiceUri, ServiceType, ServiceStatus, ServiceVersion }
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.routes.Common
import com.keepit.common.net.HttpClient
import com.keepit.model.SystemValueRepo
import com.keepit.common.db.slick.Database
import com.google.inject.{ Inject, Singleton }
import play.api.libs.json.{ JsNull, JsValue, Json }
import views.html
import java.net.InetAddress
import scala.collection.mutable.WeakHashMap
import com.keepit.common.core._

case class ClusterMemberInfo(serviceType: ServiceType, zkid: ServiceInstanceId, isLeader: Boolean, instanceInfo: AmazonInstanceInfo,
  localHostName: String, state: ServiceStatus, version: ServiceVersion, numDbConnections: Option[String])

class AdminClusterController @Inject() (
    val userActionsHelper: UserActionsHelper,
    serviceVersionMap: ServiceVersionMap,
    serviceDiscovery: ServiceDiscovery,
    zooKeeperClient: ZooKeeperClient,
    systemValueRepo: SystemValueRepo,
    db: Database) extends AdminUserActions {

  def clustersInfo: Seq[ClusterMemberInfo] = ServiceType.inProduction.flatMap { serviceType =>
    val dbConnections: Map[String, String] = if (serviceType == ServiceType.SHOEBOX) {
      val masterConnections = db.readOnlyMaster { implicit session =>
        systemValueRepo.getDbConnectionStats()
      }
      val slaveConnections = db.readOnlyReplica { implicit session =>
        systemValueRepo.getDbConnectionStats()
      }
      val hosts = slaveConnections.keys.toSet & masterConnections.keys.toSet

      hosts.map { host =>
        (host, s"${masterConnections.get(host).getOrElse(0)} / ${slaveConnections.get(host).getOrElse(0)}")
      }.toMap
    } else Map.empty

    val serviceCluster = serviceDiscovery.serviceCluster(serviceType)
    serviceCluster.allMembers.map { serviceInstance =>
      val isLeader = serviceCluster.leader.exists(_ == serviceInstance)
      val serviceVersion = serviceVersionMap(serviceInstance)
      val publicHostName = InetAddress.getByName(serviceInstance.instanceInfo.localIp.ip).getHostName
      val dbConnectionCount = dbConnections.get(serviceInstance.instanceInfo.localHostname)
      ClusterMemberInfo(serviceType, serviceInstance.id, isLeader, serviceInstance.instanceInfo, publicHostName, serviceInstance.remoteService.status, serviceVersion, dbConnectionCount)
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

  def clustersView = AdminUserPage { implicit request =>
    Ok(html.admin.adminClustersView(clustersInfo))
  }

  def zooKeeperInspector = AdminUserPage { implicit request =>
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

