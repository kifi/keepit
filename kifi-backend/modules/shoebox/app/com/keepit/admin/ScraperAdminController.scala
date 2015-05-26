package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.rover.RoverServiceClient

import views.html
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.Json
import com.keepit.common.akka.SafeFuture
import play.api.libs.iteratee.{ Concurrent, Enumerator }

class ScraperAdminController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  uriRepo: NormalizedURIRepo,
  normalizedURIRepo: NormalizedURIRepo,
  httpProxyRepo: HttpProxyRepo,
  rover: RoverServiceClient)
    extends AdminUserActions {

  val MAX_COUNT_DISPLAY = 25

  def getProxies = AdminUserPage { implicit request =>
    val proxies = db.readOnlyReplica { implicit session => httpProxyRepo.all() }
    Ok(html.admin.proxies(proxies))
  }

  def saveProxies = AdminUserPage { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_(0))
    db.readWrite { implicit session =>
      for (key <- body.keys.filter(_.startsWith("alias_")).map(_.substring(6))) {
        val id = Id[HttpProxy](key.toLong)
        val oldProxy = httpProxyRepo.get(id)
        val newProxy = oldProxy.copy(
          state = if (body.contains("active_" + key)) HttpProxyStates.ACTIVE else HttpProxyStates.INACTIVE,
          alias = body("alias_" + key),
          hostname = body("hostname_" + key),
          port = body("port_" + key).toInt,
          scheme = body("scheme_" + key),
          username = Some(body("username_" + key)).filter(!_.isEmpty),
          password = Some(body("password_" + key)).filter(!_.isEmpty)
        )

        if (newProxy != oldProxy) {
          httpProxyRepo.save(newProxy)
        }
      }
      val newProxy = body("new_alias")
      if (newProxy.nonEmpty) {
        httpProxyRepo.save(HttpProxy(
          state = if (body.contains("new_active")) HttpProxyStates.ACTIVE else HttpProxyStates.INACTIVE,
          alias = body("new_alias"),
          hostname = body("new_hostname"),
          port = body("new_port").toInt,
          scheme = body("new_scheme"),
          username = Some(body("new_username")).filter(!_.isEmpty),
          password = Some(body("new_password")).filter(!_.isEmpty)
        ))
      }
    }
    Redirect(routes.ScraperAdminController.getProxies)
  }

}

