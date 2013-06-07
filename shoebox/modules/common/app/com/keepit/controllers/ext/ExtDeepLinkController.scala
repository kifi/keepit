package com.keepit.controllers.ext

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.{ShoeboxServiceController, WebsiteController, ActionAuthenticator}
import com.keepit.common.db.slick._
import com.keepit.model._

@Singleton
class ExtDeepLinkController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  deepLinkRepo: DeepLinkRepo,
  normalizedURIRepo: NormalizedURIRepo)
    extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def handle(token: String) = AuthenticatedHtmlAction { request =>
    val deepLink = db.readOnly { implicit session => deepLinkRepo.getByToken(DeepLinkToken(token)) }

    deepLink match {
      case Some(deep) =>
        deep.recipientUserId match {
          case Some(recip) if request.userId != recip =>
            Forbidden
          case _ =>
            val nUri = db.readOnly { implicit session =>
              normalizedURIRepo.get(deep.uriId.get)
            }
            Ok(views.html.deeplink(nUri.url, deep.deepLocator.value))
        }
      case None =>
        NotFound
    }
  }

}
