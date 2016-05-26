package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ InternalOrExternalId, PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.json.TupleFormat
import com.keepit.common.time._
import com.keepit.common.util.RightBias.FromOption
import com.keepit.heimdal._
import com.keepit.model.UserValues.UserValueBooleanHandler
import com.keepit.model._
import com.keepit.shoebox.data.assemblers.{ KeepActivityAssembler, KeepInfoAssembler }
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsObject, JsString, _ }

import scala.concurrent.Future
import scala.util.{ Failure, Success }

class KeepsController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  keepRepo: KeepRepo,
  keepDecorator: KeepDecorator,
  tagCommander: TagCommander,
  keepsCommander: KeepCommander,
  keepActivityAssembler: KeepActivityAssembler,
  keepInfoAssembler: KeepInfoAssembler,
  keepExportCommander: KeepExportCommander,
  permissionCommander: PermissionCommander,
  clock: Clock,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  airbrake: AirbrakeNotifier,
  bulkTagCommander: BulkTagCommander,
  userValueRepo: UserValueRepo,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  // todo: Talk to JP and delete this if possible
  def getScreenshotUrl() = UserAction(parse.tolerantJson) { request =>
    val urlOpt = (request.body \ "url").asOpt[String]
    val urlsOpt = (request.body \ "urls").asOpt[Seq[String]]
    urlOpt.map { url =>
      NotFound(JsString("0"))
    }.orElse {
      urlsOpt.map { urls =>
        Ok(Json.obj("urls" -> JsObject(Seq.empty)))
      }
    }.getOrElse(BadRequest(JsString("0")))
  }

  def exportKeeps() = UserAction { request =>
    val exports: Seq[KeepExport] = db.readOnlyReplica { implicit ro =>
      keepRepo.getKeepExports(request.userId)
    }

    Ok(keepExportCommander.assembleKeepExport(exports))
      .withHeaders("Content-Disposition" -> "attachment; filename=keepExports.html")
      .as("text/html")
  }

  def exportOrganizationKeeps() = UserAction.async(parse.anyContent) { request =>
    request.body.asFormUrlEncoded.flatMap { form =>
      for {
        format <- form.get("format").flatMap(_.headOption.map(KeepExportFormat.apply))
        orgIds <- form.get("orgIds").map(_.map(PublicId.apply[Organization]))
      } yield (format, orgIds.toSet)
    }.orElse {
      request.body.asJson.flatMap { json =>
        for {
          format <- (json \ "format").asOpt[KeepExportFormat]
          orgIds <- (json \ "orgIds").asOpt[Set[PublicId[Organization]]]
        } yield (format, orgIds)
      }
    }.map {
      case ((format, pubIds)) =>
        val orgIds = pubIds.map { pubId => Organization.decodePublicId(pubId).get }
        keepExportCommander.exportKeeps(OrganizationKeepExportRequest(request.userId, orgIds)).map { response =>
          format match {
            case KeepExportFormat.JSON => Ok(response.get.formatAsJson).withHeaders("Content-Disposition" -> "attachment; filename=\"kifi_export.json\"")
            case KeepExportFormat.HTML => Ok(response.get.formatAsHtml).withHeaders("Content-Disposition" -> "attachment; filename=\"kifi_export.html\"")
          }
        }
    }.getOrElse(Future.successful(BadRequest))
  }

  def exportPersonalKeeps() = UserAction.async(parse.anyContent) { request =>
    request.body.asFormUrlEncoded.flatMap { form =>
      form.get("format").flatMap(_.headOption.map(KeepExportFormat.apply))
    }.orElse {
      request.body.asJson.flatMap { json =>
        (json \ "format").asOpt[KeepExportFormat]
      }
    }.map {
      case (format) =>
        keepExportCommander.exportKeeps(PersonalKeepExportRequest(request.userId)).map { response =>
          format match {
            case KeepExportFormat.JSON => Ok(response.get.formatAsJson).withHeaders("Content-Disposition" -> "attachment; filename=\"kifi_export.json\"")
            case KeepExportFormat.HTML => Ok(response.get.formatAsHtml).withHeaders("Content-Disposition" -> "attachment; filename=\"kifi_export.html\"")
          }
        }
    }.getOrElse(Future.successful(BadRequest))
  }

  def getKeepInfo(internalOrExternalId: InternalOrExternalId[Keep], maxMessagesShown: Int, authTokenOpt: Option[String]) = MaybeUserAction.async { request =>
    implicit val keepCompanion = Keep
    internalOrExternalId.parse match {
      case Failure(ex) => Future.successful(KeepFail.INVALID_KEEP_ID.asErrorResponse)
      case Success(idOrExtId) =>
        keepsCommander.getKeepInfo(idOrExtId, request.userIdOpt, maxMessagesShown, authTokenOpt)
          .map(keepInfo => Ok(Json.toJson(keepInfo)))
          .recover { case fail: KeepFail => fail.asErrorResponse }
    }
  }

  def pageCollections(sort: String, offset: Int, pageSize: Int) = UserAction { request =>
    val tags = tagCommander.tagsForUser(request.userId, offset, pageSize, TagSorting(sort))
    Ok(Json.obj("tags" -> tags))
  }

  def updateKeepTitle(pubId: PublicId[Keep]) = UserAction(parse.tolerantJson) { request =>
    import com.keepit.common.http._
    val edit = for {
      keepId <- Keep.decodePublicId(pubId).toOption.withLeft(KeepFail.INVALID_KEEP_ID)
      title <- (request.body \ "title").asOpt[String].withLeft(KeepFail.COULD_NOT_PARSE)
      editedKeep <- keepsCommander.updateKeepTitle(keepId, request.userId, title, request.userAgentOpt.flatMap(KeepEventSource.fromUserAgent))
    } yield editedKeep

    edit.fold(
      fail => fail.asErrorResponse,
      _ => NoContent
    )
  }

  def editKeepNote(keepPubId: PublicId[Keep]) = UserAction(parse.tolerantJson) { request =>
    val resultIfEverythingWentWell = for {
      keepId <- Keep.decodePublicId(keepPubId).toOption.withLeft(KeepFail.INVALID_KEEP_ID)
      newNote <- (request.body \ "note").asOpt[String].withLeft(KeepFail.COULD_NOT_PARSE)
      updatedKeep <- keepsCommander.updateKeepNote(keepId, request.userId, newNote)
    } yield updatedKeep
    resultIfEverythingWentWell.fold(
      fail => fail.asErrorResponse,
      updatedKeep => NoContent
    )
  }

  def deleteCollectionByTag(tag: String) = UserAction { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.Site).build
    val removed = db.readWrite(attempts = 3) { implicit s =>
      bulkTagCommander.removeAllForUserTag(request.userId, Hashtag(tag))
    }
    Ok(Json.obj("deleted" -> tag, "cnt" -> removed))
  }

  def renameCollectionByTag() = UserAction(parse.json) { request =>
    val oldTagName = (request.body \ "oldTagName").as[String].trim
    val newTagName = (request.body \ "newTagName").as[String].trim
    val cnt = db.readWrite { implicit session =>
      bulkTagCommander.replaceAllForUserTag(request.userId, Hashtag(oldTagName), Hashtag(newTagName))
    }
    Ok(Json.obj("renamed" -> oldTagName, "cnt" -> cnt))
  }

  def searchUserTags(query: String, limit: Option[Int] = None) = UserAction.async { request =>
    keepsCommander.searchTags(request.userId, query, limit).map { hits =>
      implicit val matchesWrites = TupleFormat.tuple2Writes[Int, Int]
      val results = JsArray(hits.map { hit => Json.obj("tag" -> hit.tag, "keepCount" -> hit.keepCount, "matches" -> hit.matches) })
      Ok(Json.obj("results" -> results))
    }
  }

  def getKeepStream(limit: Option[Int], beforeId: Option[String], afterId: Option[String], filterKind: Option[String], filterId: Option[String], maxMessagesShown: Int) = UserAction.async { request =>
    val beforeExtId = beforeId.flatMap(id => ExternalId.asOpt[Keep](id))
    val afterExtId = afterId.flatMap(id => ExternalId.asOpt[Keep](id))
    val numKeepsToShow = limit.getOrElse {
      val usesCompactCards = db.readOnlyMaster(implicit s => userValueRepo.getValue(request.userId, UserValueBooleanHandler(UserValueName.USE_MINIMAL_KEEP_CARD, default = false)))
      if (usesCompactCards) 6 else 3
    }
    val filter = filterKind.flatMap(FeedFilter(_, filterId))
    keepsCommander.getKeepStream(request.userId, numKeepsToShow, beforeExtId, afterExtId, maxMessagesShown = maxMessagesShown, sanitizeUrls = false, filterOpt = filter).map { keeps =>
      Ok(Json.obj("keeps" -> keeps))
    }
  }

  def getActivityForKeep(id: PublicId[Keep], eventsBefore: Option[DateTime], maxEvents: Int) = MaybeUserAction.async { request =>
    Keep.decodePublicId(id) match {
      case Failure(_) => Future.successful(KeepFail.INVALID_KEEP_ID.asErrorResponse)
      case Success(keepId) =>
        keepActivityAssembler.getActivityForKeep(keepId, eventsBefore, maxEvents).map { activity =>
          Ok(Json.obj("activity" -> Json.toJson(activity)))
        }
    }
  }
}
