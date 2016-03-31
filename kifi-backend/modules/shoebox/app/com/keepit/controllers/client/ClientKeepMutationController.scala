package com.keepit.controllers.client

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.discussion.DiscussionFail
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.social.Author
import play.api.libs.json.{ Json, Reads }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

class ClientKeepMutationController @Inject() (
  db: Database,
  userRepo: UserRepo,
  val userActionsHelper: UserActionsHelper,
  keepCommander: KeepCommander,
  discussionCommander: DiscussionCommander,
  clock: Clock,
  contextBuilder: HeimdalContextBuilderFactory,
  implicit val airbrake: AirbrakeNotifier,
  private implicit val defaultContext: ExecutionContext,
  private implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  def createKeep() = UserAction(parse.tolerantJson) { implicit request =>
    implicit val context = contextBuilder.withRequestInfo(request).build
    val goodResult = for {
      externalCreateRequest <- request.body.asOpt[ExternalKeepCreateRequest].map(Success(_)).getOrElse(Failure(DiscussionFail.COULD_NOT_PARSE))
      userIdMap = db.readOnlyReplica { implicit s => userRepo.convertExternalIds(externalCreateRequest.users) }
      internRequest <- for {
        // TODO(ryan): actually handle the possibility that these fail
        users <- Success(externalCreateRequest.users.map(extId => userIdMap(extId)))
        libraries <- Success(externalCreateRequest.libraries.map(pubId => Library.decodePublicId(pubId).get))
      } yield KeepInternRequest(
        author = Author.KifiUser(request.userId),
        url = externalCreateRequest.url,
        source = externalCreateRequest.source,
        attribution = None,
        title = externalCreateRequest.title,
        note = externalCreateRequest.note,
        keptAt = externalCreateRequest.keptAt,
        users = users,
        emails = externalCreateRequest.emails,
        libraries = libraries
      )
      (keep, keepIsNew) <- keepCommander.internKeep(internRequest)
    } yield keep

    goodResult.map { keep =>
      Ok(Json.obj("id" -> Keep.publicId(keep.id.get)))
    }.recover {
      case DiscussionFail.COULD_NOT_PARSE => BadRequest(Json.obj("error" -> "malformed_payload", "hint" -> ExternalKeepCreateRequest.schemaHelper.hint(request.body)))
      case fail: DiscussionFail => fail.asErrorResponse
      case fail: KeepFail => fail.asErrorResponse
    }.get
  }

  def modifyKeepRecipients(pubId: PublicId[Keep]) = UserAction.async(parse.tolerantJson) { implicit request =>
    (for {
      keepId <- Keep.decodePublicId(pubId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INVALID_KEEP_ID))
      input <- request.body.validate[ExternalKeepConnectionsDiff].map(Future.successful).getOrElse(Future.failed(DiscussionFail.COULD_NOT_PARSE))
      userIdMap = db.readOnlyReplica { implicit s => userRepo.convertExternalIds(input.users.all) }
      diff <- for {
        users <- Future.successful(input.users.map(userIdMap(_)))
        libraries <- Future.successful(input.libraries.map(libId => Library.decodePublicId(libId).get))
      } yield KeepConnectionsDiff(users = users, libraries = libraries, emails = input.emails)
      _ <- discussionCommander.modifyConnectionsForKeep(request.userId, keepId, diff)
    } yield {
      NoContent
    }).recover {
      case fail: DiscussionFail => fail.asErrorResponse
    }
  }
}
