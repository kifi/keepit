package com.keepit.controllers.client

import com.google.inject.Inject
import com.keepit.commanders.KeepQuery.ForUri
import com.keepit.commanders.{ KeepQuery, KeepQueryCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.shoebox.data.assemblers.KeepInfoAssembler
import com.keepit.shoebox.data.keep.NewKeepInfosForPage
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }

class PageInfoController @Inject() (
  db: Database,
  val userActionsHelper: UserActionsHelper,
  userRepo: UserRepo,
  keepRepo: KeepRepo,
  uriInterner: NormalizedURIInterner,
  queryCommander: KeepQueryCommander,
  keepInfoAssembler: KeepInfoAssembler,
  clock: Clock,
  implicit val airbrake: AirbrakeNotifier,
  private implicit val defaultContext: ExecutionContext,
  private implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  private def getKeepInfosForPage(viewer: Id[User], url: String, recipients: KeepRecipients): Future[NewKeepInfosForPage] = {
    val uriOpt = db.readOnlyReplica { implicit s =>
      uriInterner.getByUri(url).map(_.id.get)
    }
    uriOpt.fold(Future.successful(NewKeepInfosForPage.empty)) { uriId =>
      val query = KeepQuery(
        target = ForUri(uriId, recipients.plusUser(viewer)), // N.B. this `plusUser` is the only thing limiting visibility, be careful when messing around here
        paging = KeepQuery.Paging(fromId = None, offset = Offset(0), limit = Limit(10)),
        arrangement = None
      )
      for {
        keepIds <- db.readOnlyReplicaAsync { implicit s => queryCommander.getKeeps(Some(viewer), query) }
        (pageInfo, keepInfos) <- {
          // Launch these in parallel
          val pageInfoFut = keepInfoAssembler.assemblePageInfos(Some(viewer), Set(uriId)).map(_.get(uriId))
          val keepInfosFut = keepInfoAssembler.assembleKeepInfos(Some(viewer), keepIds.toSet)
          for (p <- pageInfoFut; k <- keepInfosFut) yield (p, k)
        }
      } yield {
        NewKeepInfosForPage(
          page = pageInfo,
          keeps = keepIds.flatMap(kId => keepInfos.get(kId).flatMap(_.getRight))
        )
      }
    }
  }
  def getKeepsByUri(url: String) = UserAction.async { implicit request =>
    getKeepInfosForPage(request.userId, url, KeepRecipients.EMPTY).map { infosForPage =>
      Ok(Json.toJson(infosForPage))
    }
  }
  def getKeepsByUriAndLibrary(url: String, libPubId: PublicId[Library]) = UserAction.async { implicit request =>
    val libId = Library.decodePublicId(libPubId).get
    getKeepInfosForPage(request.userId, url, KeepRecipients.EMPTY.plusLibrary(libId)).map { infosForPage =>
      Ok(Json.toJson(infosForPage))
    }
  }
  def getKeepsByUriAndUser(url: String, userExtId: ExternalId[User]) = UserAction.async { implicit request =>
    val userId = db.readOnlyMaster { implicit s => userRepo.convertExternalId(userExtId) }
    getKeepInfosForPage(request.userId, url, KeepRecipients.EMPTY.plusUser(userId)).map { infosForPage =>
      Ok(Json.toJson(infosForPage))
    }
  }
  def getKeepsByUriAndEmail(url: String, email: EmailAddress) = UserAction.async { implicit request =>
    getKeepInfosForPage(request.userId, url, KeepRecipients.EMPTY.plusEmailAddress(email)).map { infosForPage =>
      Ok(Json.toJson(infosForPage))
    }
  }
}
