package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.model._

import scala.concurrent.{ ExecutionContext, Future }

class AdminRoverController @Inject() (
    val userActionsHelper: UserActionsHelper,
    val db: Database,
    val roverServiceClient: RoverServiceClient,
    val normalizedUriInterner: NormalizedURIInterner,
    implicit val executionContext: ExecutionContext) extends AdminUserActions {

  def searchUrl = AdminUserPage { implicit request =>
    Ok(views.html.admin.roverSearchUrl())
  }

  def findUrl = AdminUserPage(parse.urlFormEncoded) { implicit request =>
    val url = request.body.get("url").get.head
    db.readOnlyReplica { implicit session =>
      normalizedUriInterner.getByUri(url) match {
        case Some(urlModel) => Redirect(routes.UrlController.getURIInfo(urlModel.id.get))
        case None => Ok(views.html.admin.roverSearchUrl(notFound = true, url = Some(url)))
      }
    }
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
      ).map(_ => Redirect(routes.AdminRoverController.getAllProxies()))
    }
  }

  def createProxy = AdminUserPage.async { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_(0))
    roverServiceClient.saveProxy(HttpProxy(
      state = if (body.contains("new_active")) HttpProxyStates.ACTIVE else HttpProxyStates.INACTIVE,
      alias = body("new_alias"),
      host = body("new_host"),
      port = body("new_port").toInt,
      scheme = ProxyScheme.fromName(body("new_scheme")),
      username = Some(body("new_username")).filter(!_.isEmpty),
      password = Some(body("new_password")).filter(!_.isEmpty)
    )).map(_ => Redirect(routes.AdminRoverController.getAllProxies()))
  }

  def proxyIdFromString(str: String): Option[Id[HttpProxy]] = {
    if (str == "") None
    else Some(Id(str.toLong))
  }

  def getAllUrlRules = AdminUserPage.async { implicit request =>
    for {
      urlRules <- roverServiceClient.getAllUrlRules()
      proxies <- roverServiceClient.getAllProxies()
    } yield Ok(views.html.admin.roverUrlRules(urlRules, proxies))
  }

  def saveUrlRules = AdminUserPage.async { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_(0))
    roverServiceClient.getAllUrlRules().flatMap { urlRules =>
      val idMap = urlRules.map(urlRule => (urlRule.id.get.id, urlRule)).toMap
      Future.sequence(
        for {
          key <- body.keys.filter(_.startsWith("pattern_")).map(_.substring(8))
          id = key.toLong
          oldUrlRule = idMap.get(id).get
          newUrlRule = oldUrlRule.copy(
            state = if (body.contains("active_" + key)) UrlRuleStates.ACTIVE else UrlRuleStates.INACTIVE,
            pattern = body("pattern_" + key),
            example = body("example_" + key),
            proxy = proxyIdFromString(body("proxy_" + key))
          )
          if newUrlRule != oldUrlRule
        } yield roverServiceClient.saveUrlRule(newUrlRule)
      ).map(_ => Redirect(routes.AdminRoverController.getAllUrlRules()))
    }
  }

  def createUrlRule = AdminUserPage.async { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_(0))
    roverServiceClient.saveUrlRule(UrlRule(
      state = if (body.contains("new_active")) UrlRuleStates.ACTIVE else UrlRuleStates.INACTIVE,
      pattern = body("new_pattern"),
      example = body("new_example"),
      proxy = proxyIdFromString(body("new_proxy"))
    )).map(_ => Redirect(routes.AdminRoverController.getAllUrlRules()))
  }

}
