package com.keepit.controllers.admin

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AdminController
import views.html
import com.keepit.common.service.{ServiceType, ServiceClient}
import com.keepit.common.net.HttpClient
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsNumber, JsArray, JsObject}
import com.keepit.inject.ServiceClientRegister

@Singleton
class AdminCacheController  @Inject() (
  actionAuthenticator: ActionAuthenticator,
  httpClient: HttpClient
) extends AdminController(actionAuthenticator) {
  def serviceView = AdminHtmlAction.authenticated { implicit request =>
    Ok(html.admin.cachePerformance())
  }

  def clearInMemoryCaches(service: Option[ServiceType], prefix: Option[String]) = AdminJsonAction.authenticatedAsync { implicit request =>
    val futureResponsesByService = ServiceClient.register.toSeq.collect { case serviceClient if service.map(_ == serviceClient.serviceCluster.serviceType).getOrElse(true) =>
      serviceClient.clearInMemoryCache(prefix).map { responses =>
        serviceClient.serviceCluster.serviceType -> responses
      }
    }
    Future.sequence(futureResponsesByService).map { responsesByServices =>
      val confirmations = JsObject(responsesByServices.map { case (serviceType, responses) =>
        serviceType.name -> JsObject(responses.map { response =>
          response.request.instance.get.instanceInfo.instanceId.id -> JsNumber(response.status)
        })
      })
      Ok(confirmations)
    }
  }

}

