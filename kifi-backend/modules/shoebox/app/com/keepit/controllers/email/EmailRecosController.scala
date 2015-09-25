package com.keepit.controllers.email

import com.keepit.common.time._
import com.keepit.commanders.{ LibraryInfoCommander, LibraryCommander, RawBookmarkRepresentation, KeepInterner }
import com.keepit.common.controller.{ UserRequest, ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.RecommendationSource
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.google.inject.Inject

class EmailRecosController @Inject() (
  db: Database,
  val userActionsHelper: UserActionsHelper,
  keepInterner: KeepInterner,
  clock: Clock,
  uriRepo: NormalizedURIRepo,
  libraryInfoCommander: LibraryInfoCommander,
  heimdalContextBuilder: HeimdalContextBuilderFactory)
    extends UserActions with ShoeboxServiceController with HandleDeepLinkRequests {

  def viewReco(uriId: ExternalId[NormalizedURI]) = MaybeUserAction { implicit request =>
    request match {
      case u: UserRequest[_] =>
        val uri = db.readOnlyReplica { implicit s => uriRepo.get(uriId) }
        val feedback = UriRecommendationFeedback(clicked = Some(true), source = Some(RecommendationSource.Email))
        Found(uri.url)
      case _ =>
        val uri = db.readOnlyReplica { implicit s => uriRepo.get(uriId) }
        Found(uri.url)
    }
  }

  def keepReco(uriId: ExternalId[NormalizedURI]) = UserAction { request =>
    db.readOnlyReplica(uriRepo.getOpt(uriId)(_)) map { uri =>
      val source = KeepSource.emailReco
      val hcb = heimdalContextBuilder.withRequestInfoAndSource(request, source)
      implicit val context = hcb.build

      val rawBookmark = RawBookmarkRepresentation(url = uri.url, isPrivate = None, keptAt = Some(clock.now))
      val (main, _) = db.readWrite(libraryInfoCommander.getMainAndSecretLibrariesForUser(request.userId)(_))
      keepInterner.internRawBookmark(rawBookmark, request.userId, main, source)

      Redirect(com.keepit.controllers.website.routes.HomeController.home())
    } getOrElse BadRequest
  }

  def sendReco(uriId: ExternalId[NormalizedURI]) = UserAction { request =>
    val source = KeepSource.emailReco
    val hcb = heimdalContextBuilder.withRequestInfoAndSource(request, source)
    implicit val context = hcb.build

    val uri = db.readOnlyReplica { implicit s => uriRepo.get(uriId) }
    handleAuthenticatedDeepLink(request, uri, DeepLocator("#compose"), None)
  }

}
