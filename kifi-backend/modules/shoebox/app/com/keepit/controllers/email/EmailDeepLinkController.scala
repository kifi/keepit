package com.keepit.controllers.email

import com.google.inject.Inject
import com.keepit.common.controller.{ UserRequest, UserActions, UserActionsHelper, ShoeboxServiceController }
import com.keepit.common.db.slick._
import com.keepit.model.{ DeepLink, DeepLinkRepo, DeepLinkToken, NormalizedURI, NormalizedURIRepo }

import play.api.mvc.{ Action, Result }

class EmailDeepLinkController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  deepLinkRepo: DeepLinkRepo,
  normalizedURIRepo: NormalizedURIRepo)
    extends UserActions with ShoeboxServiceController with HandleDeepLinkRequests {

  def handle(tokenString: String) = MaybeUserAction { implicit request =>
    request match {
      case u: UserRequest[_] =>
        val token = DeepLinkToken(tokenString)
        getDeepLinkAndUrl(token) map {
          case (deepLink, uri) => handleAuthenticatedDeepLink(u, uri, deepLink.deepLocator, deepLink.recipientUserId)
        } getOrElse NotFound
      case _ =>
        val token = DeepLinkToken(tokenString)
        getDeepLinkAndUrl(token) map {
          case (deepLink, uri) => handleUnauthenticatedDeepLink(request, uri, deepLink.deepLocator)
        } getOrElse NotFound
    }
  }

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
