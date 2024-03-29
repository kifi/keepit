package com.keepit.controllers.client

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.json
import com.keepit.common.mail.EmailAddress
import com.keepit.common.util.RightBias
import com.keepit.common.util.RightBias._
import com.keepit.common.core.tryExtensionOps
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.json.{ SchemaReads, TraversableFormat, KeyFormat }
import com.keepit.common.time._
import com.keepit.discussion.{ MessageSource, Message, DiscussionFail }
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.shoebox.data.assemblers.KeepInfoAssembler
import com.keepit.shoebox.data.keep.NewKeepInfo
import com.keepit.social.Author
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

class KeepMutationController @Inject() (
  db: Database,
  userRepo: UserRepo,
  val userActionsHelper: UserActionsHelper,
  permissionCommander: PermissionCommander,
  keepRepo: KeepRepo,
  keepCommander: KeepCommander,
  keepMutator: KeepMutator,
  discussionCommander: DiscussionCommander,
  keepInfoAssembler: KeepInfoAssembler,
  clock: Clock,
  contextBuilder: HeimdalContextBuilderFactory,
  private implicit val airbrake: AirbrakeNotifier,
  private implicit val defaultContext: ExecutionContext,
  private implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  private object ExternalKeepCreateRequest {
    import SchemaReads._
    final case class ExternalKeepCreateRequest(
      url: String,
      source: KeepSource,
      title: Option[String],
      note: Option[String], // will be recorded as the first comment
      keptAt: Option[DateTime],
      users: Set[ExternalId[User]],
      emails: Set[EmailAddress],
      libraries: Set[PublicId[Library]])
    private val schemaReads: SchemaReads[ExternalKeepCreateRequest] = (
      (__ \ 'url).readWithSchema[String] and
      (__ \ 'source).readWithSchema[KeepSource] and
      (__ \ 'title).readNullableWithSchema[String] and
      (__ \ 'note).readNullableWithSchema[String] and
      (__ \ 'keptAt).readNullableWithSchema[DateTime] and
      (__ \ 'users).readNullableWithSchema[Set[ExternalId[User]]].map(_ getOrElse Set.empty) and
      (__ \ 'emails).readNullableWithSchema[Set[EmailAddress]].map(_ getOrElse Set.empty) and
      (__ \ 'libraries).readNullableWithSchema[Set[PublicId[Library]]].map(_ getOrElse Set.empty)
    )(ExternalKeepCreateRequest.apply _)
    implicit val reads = schemaReads.reads
    val schema = schemaReads.schema
    val schemaHelper = json.schemaHelper(reads)
  }
  def createKeep() = UserAction.async(parse.tolerantJson) { implicit request =>
    import ExternalKeepCreateRequest._
    implicit val context = contextBuilder.withRequestInfo(request).build
    val result = for {
      externalCreateRequest <- request.body.asOpt[ExternalKeepCreateRequest].map(Future.successful).getOrElse(Future.failed(DiscussionFail.COULD_NOT_PARSE))
      userIdMap = db.readOnlyReplica { implicit s => userRepo.convertExternalIds(externalCreateRequest.users) }
      internRequest <- Future.fromTry((for {
        users <- externalCreateRequest.users.fragileMap { extId => userIdMap.get(extId).withLeft(DiscussionFail.INVALID_USER_ID) }
        libraries <- externalCreateRequest.libraries.fragileMap { pubId => Library.decodePublicId(pubId).toOption.withLeft(DiscussionFail.INVALID_LIBRARY_ID) }
      } yield KeepInternRequest.onKifi(
        keeper = request.userId,
        url = externalCreateRequest.url,
        source = externalCreateRequest.source,
        title = externalCreateRequest.title,
        note = externalCreateRequest.note,
        keptAt = externalCreateRequest.keptAt,
        recipients = KeepRecipients(libraries = libraries, emails = externalCreateRequest.emails, users = users + request.userId)
      )).asTry)
      (keep, keepIsNew, msgOpt) <- keepCommander.internKeep(internRequest)
      keepInfo <- keepInfoAssembler.assembleKeepInfos(Some(request.userId), Set(keep.id.get))
    } yield keepInfo.get(keep.id.get).flatMap(_.getRight).withLeft(keep.id.get)

    result.map { keepInfoOrKeepId =>
      keepInfoOrKeepId.fold(
        keepId => Ok(Json.obj("id" -> Keep.publicId(keepId))),
        keepInfo => Ok(NewKeepInfo.writes.writes(keepInfo))
      )
    }.recover {
      case DiscussionFail.COULD_NOT_PARSE =>
        schemaHelper.loudHintResponse(request.body, schema)
      case fail: DiscussionFail => fail.asErrorResponse
      case fail: KeepFail => fail.asErrorResponse
    }
  }

  private object ModifyKeepRecipients {
    private val schemaReads = ExternalKeepRecipientsDiff.schemaReads
    implicit val reads = schemaReads.reads
    val schema = schemaReads.schema
    val schemaHelper = json.schemaHelper(reads)
  }
  def modifyKeepRecipients(pubId: PublicId[Keep]) = UserAction.async(parse.tolerantJson) { implicit request =>
    import ModifyKeepRecipients._
    (for {
      keepId <- Keep.decodePublicId(pubId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INVALID_KEEP_ID))
      input <- request.body.validate[ExternalKeepRecipientsDiff].map(Future.successful).getOrElse(Future.failed(DiscussionFail.COULD_NOT_PARSE))
      userIdMap = db.readOnlyReplica { implicit s => userRepo.convertExternalIds(input.users.all) }
      diff <- for {
        users <- Future.successful(input.users.map(userIdMap(_)))
        libraries <- Future.successful(input.libraries.map(libId => Library.decodePublicId(libId).get))
      } yield KeepRecipientsDiff(users = users, libraries = libraries, emails = input.emails)
      _ <- if (diff.nonEmpty) discussionCommander.modifyConnectionsForKeep(request.userId, keepId, diff, input.source) else Future.successful(())
    } yield {
      NoContent
    }).recover {
      case DiscussionFail.COULD_NOT_PARSE =>
        schemaHelper.loudHintResponse(request.body, schema)
      case fail: DiscussionFail => fail.asErrorResponse
    }
  }

  def markKeepsAsRead() = UserAction.async(parse.tolerantJson) { implicit request =>
    implicit val inputFormat = KeyFormat.key2Reads[PublicId[Keep], PublicId[Message]]("keepId", "lastMessage")
    (for {
      parsedInput <- request.body.validate[Seq[(PublicId[Keep], PublicId[Message])]].map(Future.successful).getOrElse(Future.failed(DiscussionFail.COULD_NOT_PARSE))
      input = parsedInput.map {
        case (keepPubId, msgPubId) => Keep.decodePublicId(keepPubId).get -> Message.decodePublicId(msgPubId).get
      }.toMap
      unreadCountsByKeep <- discussionCommander.markKeepsAsRead(request.userId, input)
    } yield {
      implicit val outputWrites = TraversableFormat.mapWrites[PublicId[Keep], Int](_.id)
      val res = unreadCountsByKeep.map {
        case (kid, unreadCount) => Keep.publicId(kid) -> unreadCount
      }
      Ok(Json.obj("unreadCounts" -> res))
    }).recover {
      case fail: DiscussionFail => fail.asErrorResponse
    }
  }
  def deleteKeep(pubId: PublicId[Keep]) = UserAction { implicit request =>
    db.readWrite { implicit s =>
      for {
        keepId <- Keep.decodePublicId(pubId).airbrakingOption.withLeft(KeepFail.INVALID_KEEP_ID)
        _ <- RightBias.unit.filter(_ => permissionCommander.getKeepPermissions(keepId, Some(request.userId)).contains(KeepPermission.DELETE_KEEP), KeepFail.INSUFFICIENT_PERMISSIONS)
      } yield keepMutator.deactivateKeep(keepRepo.get(keepId))
    }.fold(
      fail => fail.asErrorResponse,
      _ => NoContent
    )
  }

  private object EditTitleRequest {
    import SchemaReads._
    final case class EditTitleRequest(newTitle: String, source: KeepEventSource)
    private val schemaReads: SchemaReads[EditTitleRequest] = (
      (__ \ 'newTitle).readWithSchema[String] and
      (__ \ 'source).readWithSchema[KeepEventSource]
    )(EditTitleRequest.apply _)
    implicit val reads = schemaReads.reads
    val schema = schemaReads.schema
    val schemaHelper = json.schemaHelper(reads)
  }
  def editKeepTitle(keepPubId: PublicId[Keep]) = UserAction(parse.tolerantJson) { implicit request =>
    import EditTitleRequest._
    val edit = for {
      req <- request.body.asOpt[EditTitleRequest].withLeft(DiscussionFail.COULD_NOT_PARSE)
      keepId <- Keep.decodePublicId(keepPubId).toOption.withLeft(DiscussionFail.INVALID_KEEP_ID)
      editedKeep <- keepCommander.updateKeepTitle(keepId, request.userId, req.newTitle, Some(req.source)).mapLeft(_ => DiscussionFail.INSUFFICIENT_PERMISSIONS: DiscussionFail)
    } yield editedKeep

    edit.map(_ => NoContent).getOrElse {
      case DiscussionFail.COULD_NOT_PARSE =>
        schemaHelper.loudHintResponse(request.body, schema)
      case fail => fail.asErrorResponse
    }
  }

  private object SendMessageRequest {
    import SchemaReads._
    final case class SendMessageRequest(text: String, source: MessageSource)
    private val schemaReads: SchemaReads[SendMessageRequest] = (
      (__ \ 'text).readWithSchema[String] and
      (__ \ 'source).readWithSchema[MessageSource]
    )(SendMessageRequest.apply _)
    implicit val reads = schemaReads.reads
    val schema = schemaReads.schema
    val schemaHelper = json.schemaHelper(reads)
  }
  def sendMessageOnKeep(pubId: PublicId[Keep]) = UserAction.async(parse.tolerantJson) { implicit request =>
    import SendMessageRequest._
    (for {
      req <- request.body.asOpt[SendMessageRequest].map(Future.successful).getOrElse(Future.failed(DiscussionFail.COULD_NOT_PARSE))
      keepId <- Keep.decodePublicId(pubId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INVALID_KEEP_ID))
      msg <- discussionCommander.sendMessageOnKeep(request.userId, req.text, keepId, Some(req.source))
    } yield {
      Ok(Json.toJson(msg))
    }).recover {
      case DiscussionFail.COULD_NOT_PARSE =>
        schemaHelper.loudHintResponse(request.body, schema)
      case fail: DiscussionFail => fail.asErrorResponse
    }
  }
  def deleteMessageOnKeep(keepPubId: PublicId[Keep], msgPubId: PublicId[Message]) = UserAction.async { request =>
    (for {
      keepId <- Keep.decodePublicId(keepPubId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INVALID_KEEP_ID))
      msgId <- Message.decodePublicId(msgPubId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INVALID_MESSAGE_ID))
      _ <- discussionCommander.deleteMessageOnKeep(request.userId, keepId, msgId)
    } yield {
      NoContent
    }).recover {
      case fail: DiscussionFail => fail.asErrorResponse
    }
  }

  private object EditMessageRequest {
    import SchemaReads._
    final case class EditMessageRequest(text: String, source: MessageSource)
    private val schemaReads: SchemaReads[EditMessageRequest] = (
      (__ \ 'text).readWithSchema[String] and
      (__ \ 'source).readWithSchema[MessageSource]
    )(EditMessageRequest.apply _)
    implicit val reads: Reads[EditMessageRequest] = schemaReads.reads
    val schema = schemaReads.schema
    val schemaHelper = json.schemaHelper(reads)
  }
  def editMessageOnKeep(keepPubId: PublicId[Keep], msgPubId: PublicId[Message]) = UserAction.async(parse.tolerantJson) { implicit request =>
    import EditMessageRequest._
    (for {
      keepId <- Keep.decodePublicId(keepPubId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INVALID_KEEP_ID))
      newText <- (request.body \ "newText").validate[String].map(Future.successful).getOrElse(Future.failed(DiscussionFail.COULD_NOT_PARSE))
      msgId <- Message.decodePublicId(msgPubId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INVALID_MESSAGE_ID))
      editedMsg <- discussionCommander.editMessageOnKeep(request.userId, keepId, msgId, newText)
    } yield {
      Ok(Json.obj("message" -> editedMsg))
    }).recover {
      case DiscussionFail.COULD_NOT_PARSE =>
        schemaHelper.loudHintResponse(request.body, schema)
      case fail: DiscussionFail => fail.asErrorResponse
    }
  }
}
