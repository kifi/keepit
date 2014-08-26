package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.controller.{ AuthenticatedRequest, ShoeboxServiceController, WebsiteController, ActionAuthenticator }
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.common.db.Id

import play.api.mvc.{ AnyContent, SimpleResult, Action, Request }
import play.api.libs.json.{ Json, JsString, JsObject }
import com.keepit.common.net.UserAgent
import scala.Some
import com.keepit.model.DeepLink
import com.keepit.model.DeepLocator
import com.keepit.inject.FortyTwoConfig
import java.util.NoSuchElementException
import com.keepit.common.healthcheck.AirbrakeNotifier

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

  def doHandleMobile(request: Request[_], uri: NormalizedURI, locator: DeepLocator): SimpleResult = {
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

  def mobileCheck(request: Request[_]) = {
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

class ExtDeepLinkController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  deepLinkRepo: DeepLinkRepo,
  normalizedURIRepo: NormalizedURIRepo,
  fortytwoConfig: FortyTwoConfig,
  airbrake: AirbrakeNotifier)
    extends WebsiteController(actionAuthenticator) with ShoeboxServiceController with HandleDeepLinkRequests {

  def createDeepLink() = Action(parse.tolerantJson) { request =>
    val req = request.body.asInstanceOf[JsObject]
    val initiator = (req \ "initiator").asOpt[Long].map(Id[User])
    val recipient = Id[User]((req \ "recipient").as[Long])
    val uriId = Id[NormalizedURI]((req \ "uriId").as[Long])
    val locator = (req \ "locator").as[String]

    db.readWrite { implicit session =>
      deepLinkRepo.save(
        DeepLink(
          initiatorUserId = initiator,
          recipientUserId = Some(recipient),
          uriId = Some(uriId),
          urlId = None,
          deepLocator = DeepLocator(locator)
        )
      )
    }
    Ok("")
  }

  def getDeepUrl() = Action(parse.tolerantJson) { request =>
    val req = request.body.asInstanceOf[JsObject]
    val locator = (req \ "locator").as[String]
    val recipient = Id[User]((req \ "recipient").as[Long])
    val link = db.readOnlyMaster { implicit session =>
      try {
        deepLinkRepo.getByLocatorAndUser(DeepLocator(locator), recipient).token.value
      } catch {
        case e: NoSuchElementException =>
          airbrake.notify(s"Error retrieving deep url for locator: $locator and recipient $recipient", e)
          ""
      }
    }
    val url = fortytwoConfig.applicationBaseUrl + com.keepit.controllers.ext.routes.ExtDeepLinkController.handle(link).toString()
    Ok(Json.toJson(url))
  }

  def handle(tokenString: String) = HtmlAction(
    authenticatedAction = { request =>
      val token = DeepLinkToken(tokenString)
      getDeepLinkAndUrl(token) map {
        case (deepLink, uri) => handleAuthenticatedDeepLink(request, uri, deepLink.deepLocator, deepLink.recipientUserId)
      } getOrElse NotFound
    },
    unauthenticatedAction = { request =>
      val token = DeepLinkToken(tokenString)
      getDeepLinkAndUrl(token) map {
        case (deepLink, uri) => handleUnauthenticatedDeepLink(request, uri, deepLink.deepLocator)
      } getOrElse NotFound
    })

  def handleMobile(tokenString: String) = Action { request =>
    val token = DeepLinkToken(tokenString)
    getDeepLinkAndUrl(token) map {
      case (deepLink, uri) => doHandleMobile(request, uri, deepLink.deepLocator)
    } getOrElse NotFound
  }

  private def getDeepLinkAndUrl(token: DeepLinkToken): Option[(DeepLink, NormalizedURI)] = {
    db.readOnlyMaster { implicit s =>
      for {
        deepLink <- deepLinkRepo.getByToken(token)
        uriId <- deepLink.uriId
      } yield {
        (deepLink, normalizedURIRepo.get(uriId))
      }
    }
  }

  // TODO: integrate this view into the logic/flow above (replace iphoneDeeplink)
  def handleIPhoneTempForDev(tokenString: String) = Action { request =>
    Ok(views.html.mobile.iPhoneLink())
  }
}
