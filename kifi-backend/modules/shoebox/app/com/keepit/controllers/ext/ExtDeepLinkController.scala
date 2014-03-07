package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.controller.{ShoeboxServiceController, WebsiteController, ActionAuthenticator}
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.common.db.Id

import play.api.mvc.Action
import play.api.libs.json.JsObject
import com.keepit.common.net.UserAgent

class ExtDeepLinkController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  deepLinkRepo: DeepLinkRepo,
  normalizedURIRepo: NormalizedURIRepo)
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

  def handle(token: String) = HtmlAction(
    authenticatedAction = { request =>
      getDeepLinkAndUrl(token) map { case (deepLink, url) =>
        val isIphoneApp = request.request.headers.get(USER_AGENT) exists { agentString =>
          UserAgent.fromString(agentString).isIphone
        }
        if (isIphoneApp && request.experiments.contains(ExperimentType.MOBILE_REDITECT)) {
          Ok(views.html.iphoneDeeplink(url, deepLink.deepLocator.value))
        } else {
          deepLink.recipientUserId match {
            case Some(request.userId) => Ok(
              views.html.deeplink(url, deepLink.deepLocator.value)
            )
            case _ => Redirect(url)
          }
        }
      } getOrElse NotFound
    },
    unauthenticatedAction = { request =>
      getDeepLinkAndUrl(token) map {
        case (_, url) => Redirect(url)
      } getOrElse NotFound
    })

  private def getDeepLinkAndUrl(token: String): Option[(DeepLink, String)] = {
    db.readOnly { implicit s =>
      for {
        deepLink <- deepLinkRepo.getByToken(DeepLinkToken(token))
        uriId <- deepLink.uriId
      } yield {
        (deepLink, normalizedURIRepo.get(uriId).url)
      }
    }
  }
}
