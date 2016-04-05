package com.keepit.controllers.client

import com.google.inject.Inject
import com.keepit.commanders.{ PageCommander, KeepCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.core.tryExtensionOps
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.core.mapExtensionOps
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.common.util.RightBias
import com.keepit.common.util.RightBias.FromOption
import com.keepit.model._
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.shoebox.data.assemblers.KeepInfoAssembler
import com.keepit.shoebox.data.keep.{ NewKeepInfosForPage, NewPageInfo }
import org.joda.time.DateTime
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }

class PageInfoController @Inject() (
  db: Database,
  val userActionsHelper: UserActionsHelper,
  userRepo: UserRepo,
  keepRepo: KeepRepo,
  uriInterner: NormalizedURIInterner,
  pageCommander: PageCommander,
  keepInfoAssembler: KeepInfoAssembler,
  clock: Clock,
  implicit val airbrake: AirbrakeNotifier,
  private implicit val defaultContext: ExecutionContext,
  private implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  private def getKeepInfosForPage(viewer: Option[Id[User]], url: String, recipients: KeepRecipients): Future[NewKeepInfosForPage] = {
    val uriOpt = db.readOnlyReplica { implicit s =>
      uriInterner.getByUri(url).map(_.id.get)
    }
    uriOpt.fold(Future.successful(NewKeepInfosForPage.empty)) { uriId =>
      for {
        keepSet <- pageCommander.getVisibleKeepsByUrlAndRecipients(viewer, uriId, recipients)
        pageInfo <- keepInfoAssembler.assemblePageInfos(viewer, url)
        validKeepInfos <- keepInfoAssembler.assembleKeepViews(viewer, keepSet).map(_.flatMapValues(_.getRight))
      } yield {
        NewKeepInfosForPage(
          pageInfo,
          validKeepInfos.traverseByKey
        )
      }
    }
  }
  def getKeepsByUri(url: String) = MaybeUserAction { implicit request =>
    getKeepInfosForPage(request.userIdOpt, url, KeepRecipients.EMPTY)
  }
  def getKeepsByUriAndLibrary(url: String, libPubId: PublicId[Library]) = MaybeUserAction { implicit request =>
    val libId = Library.decodePublicId(libPubId).get
    getKeepInfosForPage(request.userIdOpt, url, KeepRecipients.EMPTY.plusLibrary(libId))
  }
  def getKeepsByUriAndUser(url: String, userExtId: ExternalId[User]) = MaybeUserAction { implicit request =>
    val userId = db.readOnlyMaster { implicit s => userRepo.convertExternalId(userExtId) }
    getKeepInfosForPage(request.userIdOpt, url, KeepRecipients.EMPTY.plusUser(userId))
  }
  def getKeepsByUriAndEmail(url: String, email: EmailAddress) = MaybeUserAction { implicit request =>
    getKeepInfosForPage(request.userIdOpt, url, KeepRecipients.EMPTY.plusEmailAddress(email))
  }
}
