package com.keepit.controllers.email

import com.keepit.common.time._
import com.keepit.commanders.{ LibraryCommander, RawBookmarkRepresentation, KeepInterner }
import com.keepit.common.controller.{ UserRequest, ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.curator.CuratorServiceClient
import com.keepit.curator.model.RecommendationSource
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject

class EmailRecosController @Inject() (
  db: Database,
  val userActionsHelper: UserActionsHelper,
  keepInterner: KeepInterner,
  clock: Clock,
  uriRepo: NormalizedURIRepo,
  curator: CuratorServiceClient,
  libraryCommander: LibraryCommander,
  heimdalContextBuilder: HeimdalContextBuilderFactory)
    extends UserActions with ShoeboxServiceController with HandleDeepLinkRequests {

  def viewReco(uriId: ExternalId[NormalizedURI]) = MaybeUserAction { implicit request =>
    request match {
      case u: UserRequest[_] =>
        val uri = db.readOnlyReplica { implicit s => uriRepo.get(uriId) }
        val feedback = UriRecommendationFeedback(clicked = Some(true), source = Some(RecommendationSource.Email))
        curator.updateUriRecommendationFeedback(u.userId, uri.id.get, feedback)
        Found(uri.url)
      case _ =>
        val uri = db.readOnlyReplica { implicit s => uriRepo.get(uriId) }
        Found(uri.url)
    }
  }

  def keepReco(uriId: ExternalId[NormalizedURI]) = UserAction { request =>
    println(s"keepReco: uriId=$uriId userId=${request.userId}")
    db.readOnlyReplica(uriRepo.getOpt(uriId)(_)) map { uri =>
      val source = KeepSource.emailReco
      val hcb = heimdalContextBuilder.withRequestInfoAndSource(request, source)
      implicit val context = hcb.build

      val rawBookmark = RawBookmarkRepresentation(url = uri.url, isPrivate = None, keptAt = Some(clock.now))
      val (main, _) = db.readWrite(libraryCommander.getMainAndSecretLibrariesForUser(request.userId)(_))
      keepInterner.internRawBookmark(rawBookmark, request.userId, main, source)

      curator.updateUriRecommendationFeedback(request.userId, uri.id.get, UriRecommendationFeedback(kept = Some(true),
        source = Some(RecommendationSource.Email)))

      Redirect(com.keepit.controllers.website.routes.HomeController.home())
    } getOrElse BadRequest
  }

  def sendReco(uriId: ExternalId[NormalizedURI]) = UserAction { request =>
    val source = KeepSource.emailReco
    val hcb = heimdalContextBuilder.withRequestInfoAndSource(request, source)
    implicit val context = hcb.build

    val uri = db.readOnlyReplica { implicit s => uriRepo.get(uriId) }
    curator.updateUriRecommendationFeedback(request.userId, uri.id.get, UriRecommendationFeedback(clicked = Some(true),
      source = Some(RecommendationSource.Email)))

    handleAuthenticatedDeepLink(request, uri, DeepLocator("#compose"), None)
  }

}
