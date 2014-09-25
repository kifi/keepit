package com.keepit.controllers.email

import com.keepit.commanders.{ LibraryCommander, RawBookmarkRepresentation, KeepInterner }
import com.keepit.common.controller.{ ActionAuthenticator, ShoeboxServiceController, WebsiteController }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.curator.CuratorServiceClient
import com.keepit.curator.model.RecommendationClientType
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject

class EmailRecosController @Inject() (
  db: Database,
  actionAuthenticator: ActionAuthenticator,
  keepInterner: KeepInterner,
  uriRepo: NormalizedURIRepo,
  curator: CuratorServiceClient,
  libraryCommander: LibraryCommander,
  heimdalContextBuilder: HeimdalContextBuilderFactory)
    extends WebsiteController(actionAuthenticator) with ShoeboxServiceController with HandleDeepLinkRequests {

  def viewReco(uriId: ExternalId[NormalizedURI]) = HtmlAction(
    authenticatedAction = { request =>
      val uri = db.readOnlyReplica { implicit s => uriRepo.get(uriId) }
      val feedback = UriRecommendationFeedback(clicked = Some(true), clientType = Some(RecommendationClientType.Email))
      curator.updateUriRecommendationFeedback(request.userId, uri.id.get, feedback)
      Found(uri.url)
    },
    unauthenticatedAction = { request =>
      val uri = db.readOnlyReplica { implicit s => uriRepo.get(uriId) }
      Found(uri.url)
    }
  )

  def keepReco(uriId: ExternalId[NormalizedURI]) = HtmlAction.authenticated { request =>
    db.readOnlyReplica(uriRepo.getOpt(uriId)(_)) map { uri =>
      val source = KeepSource.emailReco
      val hcb = heimdalContextBuilder.withRequestInfoAndSource(request, source)
      implicit val context = hcb.build

      val rawBookmark = RawBookmarkRepresentation(url = uri.url, isPrivate = None)
      val (main, _) = db.readWrite(libraryCommander.getMainAndSecretLibrariesForUser(request.userId)(_))
      keepInterner.internRawBookmark(rawBookmark, request.userId, main, source)

      curator.updateUriRecommendationFeedback(request.userId, uri.id.get, UriRecommendationFeedback(kept = Some(true),
        clientType = Some(RecommendationClientType.Email)))

      Redirect(com.keepit.controllers.website.routes.KifiSiteRouter.home())
    } getOrElse BadRequest
  }

  def sendReco(uriId: ExternalId[NormalizedURI]) = HtmlAction.authenticated { request =>
    val source = KeepSource.emailReco
    val hcb = heimdalContextBuilder.withRequestInfoAndSource(request, source)
    implicit val context = hcb.build

    val uri = db.readOnlyReplica { implicit s => uriRepo.get(uriId) }
    curator.updateUriRecommendationFeedback(request.userId, uri.id.get, UriRecommendationFeedback(clicked = Some(true),
      clientType = Some(RecommendationClientType.Email)))

    handleAuthenticatedDeepLink(request, uri, DeepLocator("#compose"), None)
  }

}
