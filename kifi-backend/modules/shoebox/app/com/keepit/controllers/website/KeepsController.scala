package com.keepit.controllers.website

import com.keepit.common.crypto.PublicIdConfiguration
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
import scala.util.Try
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
  userRepo: UserRepo,
  keepRepo: KeepRepo,
  keepDecorator: KeepDecorator,
  collectionRepo: CollectionRepo,
  uriRepo: NormalizedURIRepo,
  pageInfoRepo: PageInfoRepo,
  collectionCommander: CollectionCommander,
  keepsCommander: KeepsCommander,
  userValueRepo: UserValueRepo,
  clock: Clock,
  normalizedURIInterner: NormalizedURIInterner,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  libraryCommander: LibraryCommander,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  def updateCollectionOrdering() = UserAction(parse.tolerantJson) { request =>
    implicit val externalIdFormat = ExternalId.format[Collection]
    val orderedIds = request.body.as[Seq[ExternalId[Collection]]]
    val newCollectionIds = db.readWrite { implicit s => collectionCommander.setCollectionOrdering(request.userId, orderedIds) }
    Ok(Json.obj(
      "collectionIds" -> newCollectionIds.map { id => Json.toJson(id) }
    ))
  }

  def updateCollectionIndexOrdering() = UserAction(parse.tolerantJson) { request =>
    val (id, currInd) = {
      val json = request.body
      val tagId = (json \ "tagId").as[ExternalId[Collection]]
      val currentIndex = (json \ "newIndex").as[Int]
      (tagId, currentIndex)
    }

    val newCollectionIds = collectionCommander.setCollectionIndexOrdering(request.userId, id, currInd)

    Ok(Json.obj(
      "newCollection" -> newCollectionIds.map { id => Json.toJson(id) }
    ))
  }

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

    Ok(keepsCommander.assembleKeepExport(exports))
      .withHeaders("Content-Disposition" -> "attachment; filename=keepExports.html")
      .as("text/html")
  }

  def tagKeeps(tagName: String) = UserAction(parse.tolerantJson) { implicit request =>
    val keepIds = (request.body \ "keepIds").as[Seq[ExternalId[Keep]]]
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val tag = keepsCommander.getOrCreateTag(request.userId, tagName)
    val (canEditKeep, cantEditKeeps) = keepsCommander.tagKeeps(tag, request.userId, keepIds)
    Ok(Json.obj("success" -> canEditKeep.map(_.externalId),
      "failure" -> cantEditKeeps.map(_.externalId)))
  }

  def tagKeepBulk() = UserAction(parse.tolerantJson)(editKeepTagBulk(_, true))

  def untagKeepBulk() = UserAction(parse.tolerantJson)(editKeepTagBulk(_, false))

  private def editKeepTagBulk(request: UserRequest[JsValue], isAdd: Boolean) = {
    val res = for {
      collectionId <- (request.body \ "collectionId").asOpt[ExternalId[Collection]]
      keepSet <- (request.body \ "keeps").asOpt[BulkKeepSelection]
    } yield {
      implicit val context = heimdalContextBuilder.withRequestInfo(request).build
      val numEdited = keepsCommander.editKeepTagBulk(collectionId, keepSet, request.userId, isAdd)
      Ok(Json.obj("numEdited" -> numEdited))
    }
    res getOrElse BadRequest(Json.obj("error" -> "Could not parse keep selection and/or collection id from request body"))
  }

  def getKeepInfo(id: ExternalId[Keep], withFullInfo: Boolean) = UserAction.async { request =>
    val keepOpt = db.readOnlyMaster { implicit s => keepRepo.getOpt(id).filter(_.isActive) }
    keepOpt match {
      case None => Future.successful(NotFound(Json.obj("error" -> "not_found")))
      case Some(keep) if withFullInfo => keepDecorator.decorateKeepsIntoKeepInfos(request.userIdOpt, false, Seq(keep), ProcessedImageSize.Large.idealSize, withKeepTime = true).imap { case Seq(keepInfo) => Ok(Json.toJson(keepInfo)) }
      case Some(keep) => Future.successful(Ok(Json.toJson(KeepInfo.fromKeep(keep))))
    }
  }

  def allKeeps(before: Option[String], after: Option[String], collectionOpt: Option[String], helprankOpt: Option[String], count: Int, withPageInfo: Boolean) = UserAction.async { request =>
    keepsCommander.allKeeps(before map ExternalId[Keep], after map ExternalId[Keep], collectionOpt map ExternalId[Collection], helprankOpt, count, request.userId) map { res =>
      val basicCollection = collectionOpt.flatMap { collStrExtId =>
        ExternalId.asOpt[Collection](collStrExtId).flatMap { collExtId =>
          db.readOnlyMaster(collectionRepo.getByUserAndExternalId(request.userId, collExtId)(_)).map { c =>
            BasicCollection.fromCollection(c.summary)
          }
        }
      }
      val helprank = helprankOpt map (selector => Json.obj("helprank" -> selector)) getOrElse Json.obj()
      Ok(Json.obj(
        "collection" -> basicCollection,
        "before" -> before,
        "after" -> after,
        "keeps" -> Json.toJson(res)
      ) ++ helprank)
    }
  }

  def allCollections(sort: String) = UserAction.async { request =>
    val numKeepsFuture = SafeFuture { db.readOnlyReplica { implicit s => keepRepo.getCountByUser(request.userId) } }
    val collectionsFuture = SafeFuture { collectionCommander.allCollections(sort, request.userId) }
    for {
      numKeeps <- numKeepsFuture
      collections <- collectionsFuture
    } yield {
      Ok(Json.obj(
        "keeps" -> numKeeps,
        "collections" -> collections
      ))
    }
  }

  def page(sort: String, offset: Int, pageSize: Int) = UserAction { request =>
    val tags = collectionCommander.pageCollections(sort, offset, pageSize, request.userId)
    Ok(Json.obj("tags" -> tags))
  }

  def saveCollection() = UserAction { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
    collectionCommander.saveCollection(request.userId, request.body.asJson.flatMap(Json.fromJson[BasicCollection](_).asOpt)) match {
      case Left(newColl) => Ok(Json.toJson(newColl))
      case Right(CollectionSaveFail(message)) => BadRequest(Json.obj("error" -> message))
    }
  }

  def deleteCollection(id: ExternalId[Collection]) = UserAction { request =>
    db.readOnlyMaster { implicit s => collectionRepo.getByUserAndExternalId(request.userId, id) } map { coll =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
      collectionCommander.deleteCollection(coll)
      Ok(Json.obj("deleted" -> coll.name))
    } getOrElse {
      NotFound(Json.obj("error" -> s"Collection not found for id $id"))
    }
  }

  def undeleteCollection(id: ExternalId[Collection]) = UserAction { request =>
    db.readOnlyMaster { implicit s => collectionRepo.getByUserAndExternalId(request.userId, id, Some(CollectionStates.ACTIVE)) } map { coll =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
      collectionCommander.undeleteCollection(coll)
      Ok(Json.obj("undeleted" -> coll.name))
    } getOrElse {
      NotFound(Json.obj("error" -> s"Collection not found for id $id"))
    }
  }

  def removeKeepsFromCollection(id: ExternalId[Collection]) = UserAction { request =>
    implicit val externalIdFormat = ExternalId.format[Keep]
    db.readOnlyMaster { implicit s => collectionRepo.getByUserAndExternalId(request.userId, id) } map { collection =>
      request.body.asJson.flatMap(Json.fromJson[Seq[ExternalId[Keep]]](_).asOpt) map { keepExtIds =>
        val keeps = db.readOnlyMaster { implicit s => keepExtIds.flatMap(keepRepo.getOpt(_)) }
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
        val removed = keepsCommander.removeFromCollection(collection, keeps)
        Ok(Json.obj("removed" -> removed.size))
      } getOrElse {
        BadRequest(Json.obj("error" -> "Could not parse JSON keep ids from body"))
      }
    } getOrElse {
      NotFound(Json.obj("error" -> s"Collection not found for id $id"))
    }
  }

  def keepToCollection(id: ExternalId[Collection]) = UserAction { request =>
    implicit val externalIdFormat = ExternalId.format[Keep]
    db.readOnlyMaster { implicit s =>
      collectionRepo.getByUserAndExternalId(request.userId, id)
    } map { collection =>
      request.body.asJson.flatMap(Json.fromJson[Seq[ExternalId[Keep]]](_).asOpt) map { keepExtIds =>
        val keeps = db.readOnlyMaster { implicit session => keepExtIds.map(keepRepo.get) }
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
        val added = keepsCommander.addToCollection(collection.id.get, keeps)
        Ok(Json.obj("added" -> added.size))
      } getOrElse {
        BadRequest(Json.obj("error" -> "Could not parse JSON keep ids from body"))
      }
    } getOrElse {
      NotFound(Json.obj("error" -> s"Collection not found for id $id"))
    }
  }

  def numKeeps() = UserAction { request =>
    Ok(Json.obj(
      "numKeeps" -> keepsCommander.numKeeps(request.userId)
    ))
  }

  def searchUserTags(query: String, limit: Option[Int] = None) = UserAction.async { request =>
    keepsCommander.searchTags(request.userId, query, limit).map { hits =>
      implicit val matchesWrites = TupleFormat.tuple2Writes[Int, Int]
      val results = JsArray(hits.map { hit => Json.obj("tag" -> hit.tag, "keepCount" -> hit.keepCount, "matches" -> hit.matches) })
      Ok(Json.obj("results" -> results))
    }
  }
}
