package com.keepit.controllers.email

import com.keepit.common.controller.{ ShoeboxServiceController, UserRequest }
import com.keepit.common.db.Id
import com.keepit.common.http._
import com.keepit.model.{ DeepLocator, NormalizedURI, User }
import play.api.mvc.{ Request, Result }

protected[email] trait HandleDeepLinkRequests { this: ShoeboxServiceController =>

  def handleAuthenticatedDeepLink(request: UserRequest[_], uri: NormalizedURI, locator: DeepLocator, recipientUserId: Option[Id[User]]) = {
    val (isMobileWeb, isMobileApp) = mobileCheck(request.request)
    if (isMobileApp) {
      log.info(s"redirecting user ${request.userId} on mobile app")
      Redirect(uri.url)
    } else if (isMobileWeb) {
      log.info(s"user ${request.userId} on mobile")
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
    val (isMobileWeb, isMobileApp) = mobileCheck(request)
    if (isMobileApp) {
      log.info(s"handling unknown user on mobile app")
      Redirect(uri.url)
    } else if (isMobileWeb) {
      doHandleMobile(request, uri, locator)
    } else {
      log.info(s"sending unknown user to $uri")
      Redirect(uri.url)
    }
  }

  protected def doHandleMobile(request: Request[_], uri: NormalizedURI, locator: DeepLocator): Result = {
    val (isMobileWeb, isMobileApp) = mobileCheck(request)
    if (locator.value.endsWith("#compose")) {
      log.info(s"mobile app cannot yet handle #compose")
      Redirect(uri.url)
    } else if (isMobileApp) {
      log.info(s"handling request from mobile app")
      Redirect(uri.url)
    } else if (isMobileWeb) {
      log.info(s"sending via mobile app page to $uri")
      Ok(views.html.mobile.mobileAppRedirect(s"open${locator.value}"))
    } else throw new IllegalStateException("not mobile!")
  }

  protected def mobileCheck(request: Request[_]) = {
    request.userAgentOpt map { agent =>
      (agent.isMobileWeb, agent.isMobileApp)
    } getOrElse (false, false)
  }
}
