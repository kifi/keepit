package com.keepit.controllers.ext

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.discussion.{ MessageSource, DiscussionFail }
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import play.api.libs.json.Json

import scala.concurrent.{ Future, ExecutionContext }

@Singleton
class ExtKeepController @Inject() (
  discussionCommander: DiscussionCommander,
  val userActionsHelper: UserActionsHelper,
  val db: Database,
  userRepo: UserRepo,
  libRepo: LibraryRepo,
  keepInterner: KeepInterner,
  permissionCommander: PermissionCommander,
  implicit val publicIdConfig: PublicIdConfiguration,
  contextBuilder: HeimdalContextBuilderFactory,
  implicit val ec: ExecutionContext)
    extends UserActions with ShoeboxServiceController {

  def createKeep() = UserAction(parse.tolerantJson) { implicit request =>
    import com.keepit.common.http._
    val source = request.userAgentOpt.flatMap(KeepSource.fromUserAgent).getOrElse(KeepSource.Keeper)
    implicit val context = contextBuilder.withRequestInfoAndSource(request, source).build
    val rawBookmark = request.body.as[RawBookmarkRepresentation]
    val libIds = (request.body \ "libraries").as[Set[PublicId[Library]]]
    if (libIds.size > 1) BadRequest(Json.obj("error" -> "please_no_multilib_keeps_yet"))
    val libOpt = for {
      pubId <- libIds.headOption
      libId <- Library.decodePublicId(pubId).toOption
      lib <- db.readOnlyReplica { implicit s =>
        Some(libRepo.get(libId)).filter(_ => permissionCommander.getLibraryPermissions(libId, request.userIdOpt).contains(LibraryPermission.ADD_KEEPS))
      }
    } yield lib

    val response = keepInterner.internRawBookmarksWithStatus(Seq(rawBookmark), Some(request.userId), libOpt, usersAdded = Set.empty, source)

    response.successes.headOption.map { k =>
      Ok(Json.obj("id" -> Keep.publicId(k.id.get)))
    }.get // just throw exceptions if we fail
  }

  def sendMessageOnKeep(pubId: PublicId[Keep]) = UserAction.async(parse.tolerantJson) { implicit request =>
    import com.keepit.common.http._
    val source = (request.body \ "source").asOpt[MessageSource].orElse(request.userAgentOpt.flatMap(ua => MessageSource.fromStr(ua.name)))
    (for {
      text <- (request.body \ "text").asOpt[String].map(Future.successful).getOrElse(Future.failed(DiscussionFail.MISSING_MESSAGE_TEXT))
      keepId <- Keep.decodePublicId(pubId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INVALID_KEEP_ID))
      msg <- discussionCommander.sendMessageOnKeep(request.userId, text, keepId, source)
    } yield {
      Ok(Json.toJson(msg))
    }).recover {
      case fail: DiscussionFail => fail.asErrorResponse
    }
  }

  def modifyConnectionsForKeep(pubId: PublicId[Keep]) = UserAction.async(parse.tolerantJson) { implicit request =>
    import com.keepit.common.http._
    (for {
      keepId <- Keep.decodePublicId(pubId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INVALID_KEEP_ID))
      input <- request.body.validate[ExternalKeepRecipientsDiff].map { diff =>
        diff.source.map(src => log.info(s"[sourceTrack] request by user ${request.userId} HAS source ${src.value}")).getOrElse(log.info(s"[sourceTrack] request by user ${request.userId} DOESNT HAVE source"))
        Future.successful(diff)
      }.getOrElse(Future.failed(DiscussionFail.COULD_NOT_PARSE))
      diff <- db.readOnlyReplicaAsync { implicit s =>
        val userIdMap = userRepo.convertExternalIds(input.users.all)
        val libraryIdMap = input.libraries.all.map(libPubId => libPubId -> Library.decodePublicId(libPubId).get).toMap
        KeepRecipientsDiff(users = input.users.map(userIdMap(_)), libraries = input.libraries.map(libraryIdMap(_)), emails = input.emails)
      }
      _ <- if (diff.nonEmpty) discussionCommander.modifyConnectionsForKeep(request.userId, keepId, diff, input.source.orElse(request.userAgentOpt.flatMap(KeepEventSource.fromUserAgent))) else Future.successful(())
    } yield {
      NoContent
    }).recover {
      case fail: DiscussionFail => fail.asErrorResponse
    }
  }
}
