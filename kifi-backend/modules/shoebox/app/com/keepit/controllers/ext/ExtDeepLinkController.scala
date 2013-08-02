package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.controller.{ShoeboxServiceController, WebsiteController, ActionAuthenticator}
import com.keepit.common.db.slick._
import com.keepit.model._

class ExtDeepLinkController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  deepLinkRepo: DeepLinkRepo,
  normalizedURIRepo: NormalizedURIRepo)
    extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def handle(token: String) = HtmlAction(authenticatedAction = { request =>
    getDeepLinkAndUrl(token) map { case (deepLink, url) =>
      deepLink.recipientUserId match {
        case Some(request.userId) => Ok(views.html.deeplink(url, deepLink.deepLocator.value))
        case _ => Redirect(url)
      }
    } getOrElse NotFound
  }, unauthenticatedAction = { request =>
    getDeepLinkAndUrl(token) map { case (_, url) => Redirect(url) } getOrElse NotFound
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
