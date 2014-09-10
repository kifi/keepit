package com.keepit.controllers.email

import com.google.inject.Inject
import com.keepit.common.controller.{ ShoeboxServiceController, WebsiteController, ActionAuthenticator }
import com.keepit.common.db.slick._
import com.keepit.model.{ DeepLink, DeepLinkRepo, DeepLinkToken, DeepLocator, NormalizedURI, NormalizedURIRepo }

import play.api.mvc.{ Action, Result }

class EmailDeepLinkController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  deepLinkRepo: DeepLinkRepo,
  normalizedURIRepo: NormalizedURIRepo)
    extends WebsiteController(actionAuthenticator) with ShoeboxServiceController with HandleDeepLinkRequests {

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

  // TODO: integrate this view into the logic/flow above (replace iphoneDeeplink)
  def handleIPhoneTempForDev(tokenString: String) = Action { request =>
    Ok(views.html.mobile.iPhoneLink())
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

}
