package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model.{ URLRepo, NormalizedURIRepo }
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.model.{ ProxyScheme, HttpProxyStates, HttpProxy }

import scala.concurrent.{ Future, ExecutionContext }

class AdminRoverController @Inject() (
    val userActionsHelper: UserActionsHelper,
    val db: Database,
    val uriRepo: NormalizedURIRepo,
    val urlRepo: URLRepo,
    val roverServiceClient: RoverServiceClient,
    implicit val executionContext: ExecutionContext) extends AdminUserActions {

  def findUrl = AdminUserPage { implicit request =>
    ???
  }

  def getAllProxies = AdminUserPage.async { implicit request =>
    roverServiceClient.getAllProxies().map { proxies =>
      Ok(views.html.admin.roverProxies(proxies))
    }
  }

  def saveProxies = AdminUserPage.async { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_(0))
    roverServiceClient.getAllProxies().flatMap { proxies =>
      val idMap = proxies.map(proxy => (proxy.id.get.id, proxy)).toMap
      Future.sequence(
        for {
          key <- body.keys.filter(_.startsWith("alias_")).map(_.substring(6))
          id = key.toLong
          oldProxy = idMap.get(id).get
          newProxy = oldProxy.copy(
            state = if (body.contains("active_" + key)) HttpProxyStates.ACTIVE else HttpProxyStates.INACTIVE,
            alias = body("alias_" + key),
            host = body("host_" + key),
            port = body("port_" + key).toInt,
            scheme = ProxyScheme.fromName(body("scheme_" + key)),
            username = Some(body("username_" + key)).filter(!_.isEmpty),
            password = Some(body("password_" + key)).filter(!_.isEmpty)
          )
          if newProxy != oldProxy
        } yield roverServiceClient.saveProxy(newProxy)
      )
    }.flatMap { _ =>
      Future.successful(body("new_alias"))
        .filter(_.nonEmpty)
        .flatMap { _ =>
          roverServiceClient.saveProxy(HttpProxy(
            state = if (body.contains("new_active")) HttpProxyStates.ACTIVE else HttpProxyStates.INACTIVE,
            alias = body("new_alias"),
            host = body("new_host"),
            port = body("new_port").toInt,
            scheme = ProxyScheme.fromName(body("new_scheme")),
            username = Some(body("new_username")).filter(!_.isEmpty),
            password = Some(body("new_password")).filter(!_.isEmpty)
          ))
        }
        .fallbackTo(Future.successful(()))
        .map(_ => Redirect(routes.AdminRoverController.getAllProxies()))
    }
  }

}
