package com.keepit.controllers.website

import com.keepit.common.crypto.{ InternalOrExternalId, PublicId, PublicIdConfiguration }
import com.keepit.common.healthcheck.AirbrakeNotifier
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject

import com.keepit.heimdal._
import com.keepit.commanders._
import com.keepit.common.controller.{ UserActions, UserActionsHelper, UserRequest, ShoeboxServiceController }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.common.akka.SafeFuture

import play.api.libs.json._
import scala.util.{ Failure, Success, Try }
import org.joda.time.Seconds
import scala.concurrent.Future
import com.keepit.commanders.CollectionSaveFail
import play.api.libs.json.JsString
import play.api.libs.json.JsObject
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.common.json.TupleFormat
import com.keepit.common.core._

class KeepsController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  keepRepo: KeepRepo,
  keepDecorator: KeepDecorator,
  collectionRepo: CollectionRepo,
  collectionCommander: CollectionCommander,
  keepsCommander: KeepCommander,
  keepExportCommander: KeepExportCommander,
  clock: Clock,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  airbrake: AirbrakeNotifier,
  keepToCollectionRepo: KeepToCollectionRepo,
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

  def getKeepInfo(internalOrExternalId: InternalOrExternalId[Keep]) = UserAction.async { request =>
    implicit val keepCompanion = Keep
    internalOrExternalId.parse match {
      case Failure(ex) => Future.successful(BadRequest(Json.obj("error" -> "invalid_id", "details" -> ex.getMessage)))
      case Success(idOrExtId) =>
        val keepOpt = db.readOnlyReplica { implicit s =>
          idOrExtId.fold[Option[Keep]](
            { id: Id[Keep] => keepRepo.getOption(id) }, { extId: ExternalId[Keep] => keepRepo.getByExtId(extId) }
          )
        }
        keepOpt match {
          case None => Future.successful(NotFound(Json.obj("error" -> "not_found")))
          case Some(keep) => keepDecorator.decorateKeepsIntoKeepInfos(request.userIdOpt, false, Seq(keep), ProcessedImageSize.Large.idealSize, sanitizeUrls = false).imap { case Seq(keepInfo) => Ok(Json.toJson(keepInfo)) }
        }
    }
  }

  def pageCollections(sort: String, offset: Int, pageSize: Int) = UserAction { request =>
    val tags = collectionCommander.pageCollections(sort, offset, pageSize, request.userId)
    Ok(Json.obj("tags" -> tags))
  }

  def deleteCollection(id: ExternalId[Collection]) = UserAction { request =>
    db.readOnlyMaster { implicit s => collectionRepo.getByUserAndExternalId(request.userId, id) } map { coll =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
      val keepIds = db.readOnlyReplica { implicit session =>
        keepToCollectionRepo.getKeepsForTag(coll.id.get).toSet
      }
      collectionCommander.deleteCollection(coll)
      val cnt = keepsCommander.removeTagFromKeeps(keepIds, coll.name)
      Ok(Json.obj("deleted" -> coll.name, "cnt" -> cnt))
    } getOrElse {
      NotFound(Json.obj("error" -> s"Collection not found for id $id"))
    }
  }

  def renameCollection(id: ExternalId[Collection]) = UserAction(parse.json) { request =>
    val newTagName = (request.body \ "newTagName").as[String].trim
    db.readOnlyMaster { implicit s => collectionRepo.getByUserAndExternalId(request.userId, id) } map { coll =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
      val keepIds = db.readOnlyReplica { implicit session =>
        keepToCollectionRepo.getKeepsForTag(coll.id.get).toSet
      }
      val cnt = keepsCommander.replaceTagOnKeeps(keepIds, coll.name, Hashtag(newTagName))
      Ok(Json.obj("deleted" -> coll.name, "cnt" -> cnt))
    } getOrElse {
      NotFound(Json.obj("error" -> s"Collection not found for id $id"))
    }
  }

  def searchUserTags(query: String, limit: Option[Int] = None) = UserAction.async { request =>
    keepsCommander.searchTags(request.userId, query, limit).map { hits =>
      implicit val matchesWrites = TupleFormat.tuple2Writes[Int, Int]
      val results = JsArray(hits.map { hit => Json.obj("tag" -> hit.tag, "keepCount" -> hit.keepCount, "matches" -> hit.matches) })
      Ok(Json.obj("results" -> results))
    }
  }

  def getKeepStream(limit: Int, beforeId: Option[String], afterId: Option[String]) = UserAction.async { request =>
    val beforeExtId = beforeId.flatMap(id => ExternalId.asOpt[Keep](id))
    val afterExtId = afterId.flatMap(id => ExternalId.asOpt[Keep](id))

    keepsCommander.getKeepStream(request.userId, limit, beforeExtId, afterExtId, sanitizeUrls = false).map { keeps =>
      Ok(Json.obj("keeps" -> keeps))
    }
  }
}
