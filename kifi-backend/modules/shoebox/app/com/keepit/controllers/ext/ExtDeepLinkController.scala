package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.controller.{ShoeboxServiceController, WebsiteController, ActionAuthenticator}
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.common.db.Id

import play.api.mvc.{SimpleResult, Action, Request}
import play.api.libs.json.{Json, JsString, JsObject}
import com.keepit.common.net.UserAgent
import scala.Some
import com.keepit.model.DeepLink
import com.keepit.model.DeepLocator
import com.keepit.inject.FortyTwoConfig

class ExtDeepLinkController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  deepLinkRepo: DeepLinkRepo,
  normalizedURIRepo: NormalizedURIRepo,
  fortytwoConfig: FortyTwoConfig)
    extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def createDeepLink() = Action(parse.tolerantJson) { request =>
    val req = request.body.asInstanceOf[JsObject]
    val initiator = Id[User]((req \ "initiator").as[Long])
    val recipient = Id[User]((req \ "recipient").as[Long])
    val uriId = Id[NormalizedURI]((req \ "uriId").as[Long])
    val locator = (req \ "locator").as[String]

    db.readWrite{ implicit session => deepLinkRepo.save(
      DeepLink(
        initiatorUserId = Some(initiator),
        recipientUserId = Some(recipient),
        uriId = Some(uriId),
        urlId = None,
        deepLocator = DeepLocator(locator)
      )
    )}
    Ok("")
  }

  def getDeepUrl() = Action(parse.tolerantJson) { request =>
    val req = request.body.asInstanceOf[JsObject]
    val locator = (req \ "locator").as[String]
    val recipient = Id[User]((req \ "recipient").as[Long])
    val link = db.readOnly { implicit session => deepLinkRepo.getByLocatorAndUser(DeepLocator(locator), recipient) }
    val url = fortytwoConfig.applicationBaseUrl + "/" + com.keepit.controllers.ext.routes.ExtDeepLinkController.handle(link.token.value).toString()
    Ok(Json.toJson(url))
  }

  private def mobileCheck(request: Request[_]) = {
    request.headers.get(USER_AGENT).headOption map { agentString =>
      if (agentString == null || agentString.isEmpty) {
        (false, false)
      } else {
        val agent = UserAgent.fromString(agentString)
        (agent.isIphone, agent.isKifiIphoneApp)
      }
    } getOrElse (false, false)
  }

  def handle(tokenString: String) = HtmlAction(
    authenticatedAction = { request =>
      val token = DeepLinkToken(tokenString)
      getDeepLinkAndUrl(token) map { case (deepLink, uri) =>
        val (isIphone, isKifiIphoneApp) = mobileCheck(request.request)
        log.info(s"handling user ${request.userId} with iphone: $isIphone & app: $isKifiIphoneApp")
        if (isKifiIphoneApp) {
          Redirect(uri.url)
        } else if (isIphone) {
          doHandleMobile(request, tokenString)
        } else {
          deepLink.recipientUserId match {
            case Some(request.userId) =>
              log.info(s"sending user ${request.userId} to $uri")
              Ok(views.html.deeplink(uri.url, deepLink.deepLocator.value))
            case _ =>
              log.info(s"sending unknown user to $uri with authenticated session")//is that possible ???
              Redirect(uri.url)
          }
        }
      } getOrElse NotFound
    },
    unauthenticatedAction = { request =>
      val token = DeepLinkToken(tokenString)
      getDeepLinkAndUrl(token) map {
        case (deepLink, uri) =>
          val (isIphone, isKifiIphoneApp) = mobileCheck(request)
          log.info(s"handling unknown user with iphone: $isIphone & app: $isKifiIphoneApp")
          if (isKifiIphoneApp) {
            Redirect(uri.url)
          } else if (isIphone) {
            doHandleMobile(request, tokenString)
          } else {
            log.info(s"sending unknown user directly to the url $uri")
            Redirect(uri.url)
          }
      } getOrElse NotFound
    })

  def handleMobile(tokenString: String) = Action { request =>
    doHandleMobile(request, tokenString)
  }

  private def doHandleMobile(request: Request[_], tokenString: String): SimpleResult = {
    val (isIphone, isKifiIphoneApp) = mobileCheck(request)
    val token = DeepLinkToken(tokenString)
    val result: Option[SimpleResult] = getDeepLinkAndUrl(token) map {
      case (deepLink, uri) =>
        if (isKifiIphoneApp) {
          Redirect(uri.url)
        } else if (isIphone) {
          log.info(s"sending user to $uri via iphone app page")
          Ok(views.html.iphoneDeeplink(uri.url, deepLink.deepLocator.value))
        } else throw new IllegalStateException("not mobile!")
    }
    result getOrElse NotFound
  }

  private def getDeepLinkAndUrl(token: DeepLinkToken): Option[(DeepLink, NormalizedURI)] = {
    db.readOnly { implicit s =>
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
