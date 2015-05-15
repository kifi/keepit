package com.keepit.controllers.admin

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import views.html
import com.keepit.common.service.{ FortyTwoServices, ServiceType, ServiceClient }
import com.keepit.common.net.HttpClient
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsBoolean, JsNumber, JsArray, JsObject, JsString }
import play.api.libs.json._
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.cache.InMemoryCachePlugin

@Singleton
class AdminCacheController @Inject() (
    val userActionsHelper: UserActionsHelper,
    httpClient: HttpClient,
    localCache: InMemoryCachePlugin,
    serviceDiscovery: ServiceDiscovery) extends AdminUserActions {
  def serviceView = AdminUserPage { implicit request =>
    Ok(html.admin.cacheOverview())
  }

  def getCacheEntry(key: String) = AdminUserAction { implicit request =>
    localCache.get(key) match {
      case Some(value) => Ok(Json.obj(key -> value.toString))
      case _ => NoContent
    }
  }

  def deleteCacheEntry(key: String) = AdminUserAction { implicit request =>
    localCache.remove(key)
    Ok
  }

  def setCacheEntry(key: String, value: String) = AdminUserAction { implicit request =>
    localCache.set(key, value)
    Ok
  }

  def clearLocalCaches(service: String, prefix: String) = AdminUserAction.async { implicit request =>
    def shouldBeCleared(serviceType: ServiceType) = service == "all" || ServiceType.fromString(service.toUpperCase) == serviceType
    val prefixOpt = Some(prefix).filter(_.nonEmpty)
    val futureResponsesByService = ServiceClient.register.toSeq.collect {
      case serviceClient if shouldBeCleared(serviceClient.serviceCluster.serviceType) =>
        val serviceType = serviceClient.serviceCluster.serviceType

        val clearedLocalInstance = if (serviceType == serviceDiscovery.thisService) {
          localCache.removeAll(prefixOpt)
          Some(serviceDiscovery.thisInstance.get.instanceInfo.instanceId.id -> true)
        } else None

        serviceClient.removeAllFromLocalCaches(prefixOpt).map { responses =>
          serviceClient.serviceCluster.serviceType -> {
            val clearedRemoteInstances = responses.map { response =>
              response.request.instance.get.instanceInfo.instanceId.id -> (response.status == OK)
            }
            clearedRemoteInstances ++ clearedLocalInstance.toSeq
          }
        }
    }

    Future.sequence(futureResponsesByService).map { responsesByServices =>
      val confirmations = JsObject(responsesByServices.map {
        case (serviceType, responses) =>
          serviceType.name -> JsObject(responses.map {
            case (instance, isCleared) =>
              instance -> JsBoolean(isCleared)
          })
      })
      Ok(confirmations)
    }
  }

}

