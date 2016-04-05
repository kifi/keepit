package com.keepit.controllers.client

import com.google.inject.Inject
import com.keepit.commanders.{PageCommander, KeepCommander}
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.core.tryExtensionOps
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.common.util.RightBias
import com.keepit.common.util.RightBias.FromOption
import com.keepit.model._
import com.keepit.shoebox.data.assemblers.KeepInfoAssembler
import com.keepit.shoebox.data.keep.{NewKeepInfosForPage, NewPageInfo}
import org.joda.time.DateTime
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }

class PageInfoController @Inject() (
  db: Database,
  val userActionsHelper: UserActionsHelper,
  keepRepo: KeepRepo,
  pageCommander: PageCommander,
  keepInfoAssembler: KeepInfoAssembler,
  clock: Clock,
  implicit val airbrake: AirbrakeNotifier,
  private implicit val defaultContext: ExecutionContext,
  private implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {



  private def getKeepInfosForPage(viewer: Option[Id[User]], url: String, recipients: KeepRecipients): Future[NewKeepInfosForPage] = {
    for {
      keepSet <- pageCommander.getVisibleKeepsByUrlAndRecipients(viewer, url, recipients)
      pageInfo <- keepInfoAssembler.getPageInfo(viewer, url)
      keepInfos <- keepInfoAssembler.assembleKeepViews(viewer, keepSet)
    } yield NewKeepInfosForPage(pageInfo keepInfos.values.toSeq.flatMap(_.getRight).sortBy(_.keep.id))
  }
  def getKeepsByUriAndLibrary(url: String, libPubId: PublicId[Library]) = MaybeUserAction { implicit request =>
  }
  def getKeepsByUriAndUser(url: String, userExtId: ExternalId[User]) = MaybeUserAction { implicit request =>
    ???
  }
  def getKeepsByUriAndEmail(url: String, email: EmailAddress) = MaybeUserAction { implicit request =>
    ???
  }
}
