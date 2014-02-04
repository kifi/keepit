package com.keepit.controllers.admin

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AdminController
import views.html
import com.keepit.common.service.{FortyTwoServices, ServiceType, ServiceClient}
import com.keepit.common.net.HttpClient
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsBoolean, JsNumber, JsArray, JsObject}
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.cache.InMemoryCachePlugin
import org.apache.http.HttpStatus

@Singleton
class AdminCacheController  @Inject() (
  actionAuthenticator: ActionAuthenticator,
  httpClient: HttpClient,
  localCache: InMemoryCachePlugin,
  serviceDiscovery: ServiceDiscovery
) extends AdminController(actionAuthenticator) {
  def serviceView = AdminHtmlAction.authenticated { implicit request =>
    Ok(html.admin.cachePerformance())
  }

  def clearLocalCaches(service: Option[String], prefix: Option[String]) = AdminJsonAction.authenticatedAsync { implicit request =>
    def shouldBeCleared(serviceType: ServiceType) = service.map(ServiceType.fromString).map(_ == serviceType).getOrElse(true)
    val futureResponsesByService = ServiceClient.register.toSeq.collect { case serviceClient if shouldBeCleared(serviceClient.serviceCluster.serviceType) =>
      val serviceType = serviceClient.serviceCluster.serviceType

      val clearedLocalInstance = if (serviceType == serviceDiscovery.thisService) {
        localCache.removeAll(prefix)
        Some(serviceDiscovery.thisInstance.get.instanceInfo.instanceId.id -> true)
      } else None

      serviceClient.removeAllFromLocalCache(prefix).map { responses =>
        serviceClient.serviceCluster.serviceType -> {
          val clearedRemoteInstances = responses.map { response =>
            response.request.instance.get.instanceInfo.instanceId.id -> (response.status == HttpStatus.SC_OK)
          }
          clearedRemoteInstances ++ clearedLocalInstance.toSeq
        }
      }
    }

    Future.sequence(futureResponsesByService).map { responsesByServices =>
      val confirmations = JsObject(responsesByServices.map { case (serviceType, responses) =>
        serviceType.name -> JsObject(responses.map { case (instance, isCleared) =>
          instance -> JsBoolean(isCleared)
        })
      })
      Ok(confirmations)
    }
  }

}

