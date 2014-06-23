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
import java.util.NoSuchElementException
import com.keepit.common.healthcheck.AirbrakeNotifier

class ExtDeepLinkController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  deepLinkRepo: DeepLinkRepo,
  normalizedURIRepo: NormalizedURIRepo,
  fortytwoConfig: FortyTwoConfig,
  airbrake: AirbrakeNotifier)
    extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def createDeepLink() = Action(parse.tolerantJson) { request =>
    val req = request.body.asInstanceOf[JsObject]
    val initiator = (req \ "initiator").asOpt[Long].map(Id[User](_))
    val recipient = Id[User]((req \ "recipient").as[Long])
    val uriId = Id[NormalizedURI]((req \ "uriId").as[Long])
    val locator = (req \ "locator").as[String]

    db.readWrite{ implicit session => deepLinkRepo.save(
      DeepLink(
        initiatorUserId = initiator,
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
    val link = db.readOnly { implicit session =>
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
        if (isKifiIphoneApp) {
          log.info(s"redirecting user ${request.userId} on iphone app")
          Redirect(uri.url)
        } else if (isIphone) {
          log.info(s"user ${request.userId} on iphone")
          doHandleMobile(request, tokenString)
        } else {
          deepLink.recipientUserId match {
            case None | Some(request.userId) =>
              log.info(s"sending user ${request.userId} to $uri")
              Ok(views.html.deeplink(uri.url, deepLink.deepLocator.value))
            case _ =>
              log.info(s"sending wrong user ${request.userId} to $uri")
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
          if (isKifiIphoneApp) {
            log.info(s"handling unknown user on iphone app")
            Redirect(uri.url)
          } else if (isIphone) {
            doHandleMobile(request, tokenString)
          } else {
            log.info(s"sending unknown user to $uri")
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
          log.info(s"handling request from iphone app")
          Redirect(uri.url)
        } else if (isIphone) {
          log.info(s"sending via iphone app page to $uri")
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
