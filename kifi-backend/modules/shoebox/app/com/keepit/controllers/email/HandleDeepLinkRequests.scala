package com.keepit.controllers.email

import com.keepit.common.controller.{ AuthenticatedRequest, ShoeboxServiceController }
import com.keepit.common.db.Id
import com.keepit.common.net.UserAgent
import com.keepit.model.{ User, DeepLocator, NormalizedURI }
import play.api.mvc.{ SimpleResult, Request }

trait HandleDeepLinkRequests { this: ShoeboxServiceController =>

  def handleAuthenticatedDeepLink(request: AuthenticatedRequest[_], uri: NormalizedURI, locator: DeepLocator, recipientUserId: Option[Id[User]]) = {
    val (isIphone, isKifiIphoneApp) = mobileCheck(request.request)
    if (isKifiIphoneApp) {
      log.info(s"redirecting user ${request.userId} on iphone app")
      Redirect(uri.url)
    } else if (isIphone) {
      log.info(s"user ${request.userId} on iphone")
      doHandleMobile(request, uri, locator)
    } else {
      recipientUserId match {
        case None | Some(request.userId) =>
          log.info(s"sending user ${request.userId} to $uri")
          Ok(views.html.deeplink(uri.url, locator.value))
        case _ =>
          log.info(s"sending wrong user ${request.userId} to $uri")
          Redirect(uri.url)
      }
    }
  }

  def handleUnauthenticatedDeepLink(request: Request[_], uri: NormalizedURI, locator: DeepLocator) = {
    val (isIphone, isKifiIphoneApp) = mobileCheck(request)
    if (isKifiIphoneApp) {
      log.info(s"handling unknown user on iphone app")
      Redirect(uri.url)
    } else if (isIphone) {
      doHandleMobile(request, uri, locator)
    } else {
      log.info(s"sending unknown user to $uri")
      Redirect(uri.url)
    }
  }

  protected def doHandleMobile(request: Request[_], uri: NormalizedURI, locator: DeepLocator): SimpleResult = {
    val (isIphone, isKifiIphoneApp) = mobileCheck(request)
    if (locator.value.endsWith("#compose")) {
      log.info(s"iphone app cannot yet handle #compose")
      Redirect(uri.url)
    } else if (isKifiIphoneApp) {
      log.info(s"handling request from iphone app")
      Redirect(uri.url)
    } else if (isIphone) {
      log.info(s"sending via iphone app page to $uri")
      Ok(views.html.iphoneDeeplink(uri.url, locator.value))
    } else throw new IllegalStateException("not mobile!")
  }

  protected def mobileCheck(request: Request[_]) = {
    request.headers.get(USER_AGENT).headOption map { agentString =>
      if (agentString == null || agentString.isEmpty) {
        (false, false)
      } else {
        val agent = UserAgent.fromString(agentString)
        (agent.isIphone, agent.isKifiIphoneApp)
      }
    } getOrElse (false, false)
  }
}
