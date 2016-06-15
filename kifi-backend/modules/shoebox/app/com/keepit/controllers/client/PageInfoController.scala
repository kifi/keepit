package com.keepit.controllers.client

import com.google.inject.Inject
import com.keepit.commanders.KeepQuery.{ FirstOrder, Paging }
import com.keepit.commanders.gen.BasicLibraryGen
import com.keepit.commanders.{ KeepQuery, KeepQueryCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.core.mapExtensionOps
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.json
import com.keepit.common.json.SchemaReads
import com.keepit.common.time._
import com.keepit.common.util.PaginationContext
import com.keepit.common.util.RightBias._
import com.keepit.model._
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.shoebox.data.assemblers.KeepInfoAssemblerConfig.KeepViewAssemblyOptions
import com.keepit.shoebox.data.assemblers.{ KeepInfoAssembler, KeepInfoAssemblerConfig }
import com.keepit.shoebox.data.keep.{ NewKeepInfosForPage, NewPageInfo }
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }

class PageInfoController @Inject() (
  db: Database,
  val userActionsHelper: UserActionsHelper,
  userRepo: UserRepo,
  keepRepo: KeepRepo,
  uriInterner: NormalizedURIInterner,
  queryCommander: KeepQueryCommander,
  keepInfoAssembler: KeepInfoAssembler,
  keepToLibraryRepo: KeepToLibraryRepo,
  clock: Clock,
  basicLibraryGen: BasicLibraryGen,
  implicit val airbrake: AirbrakeNotifier,
  private implicit val defaultContext: ExecutionContext,
  private implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  private def getAllLibrariesForUserOnUrl(viewer: Id[User], url: String, limit: Int): Seq[BasicLibrary] = {
    db.readOnlyReplica { implicit s =>
      uriInterner.getByUri(url).map(_.id.get).fold(Seq.empty[BasicLibrary]) { uriId =>
        val query = KeepQuery(
          target = FirstOrder(uriId, viewer),
          paging = Paging(filter = None, offset = 0, limit = Math.min(limit, 50)),
          arrangement = None
        )
        val keepIds = keepRepo.getKeepIdsForQuery(query).toSet
        val libs = keepToLibraryRepo.getAllByKeepIds(keepIds).values.flatten.map(_.libraryId)
        basicLibraryGen.getBasicLibraries(libs.toSet).values.toList
      }
    }
  }

  private object GetFirstOrderLibrariesByUri {
    import json.SchemaReads._
    final case class GetFirstOrderKeepsByUri(
      url: String,
      limit: Int)
    val schemaReads: SchemaReads[GetFirstOrderKeepsByUri] = (
      (__ \ 'url).readWithSchema[String] and
      (__ \ 'limit).readNullableWithSchema[Int].map(_ getOrElse 100)
    )(GetFirstOrderKeepsByUri.apply _)
    implicit val reads = schemaReads.reads
    val schema = schemaReads.schema
    val schemaHelper = json.schemaHelper(reads)
  }
  def getFirstOrderLibrariesForUserByUri() = UserAction(parse.tolerantJson) { implicit request =>
    import GetFirstOrderLibrariesByUri._
    val resultIfEverythingChecksOut = for {
      input <- request.body.asOpt[GetFirstOrderKeepsByUri].withLeft(KeepFail.COULD_NOT_PARSE)
    } yield getAllLibrariesForUserOnUrl(request.userId, input.url, input.limit)

    resultIfEverythingChecksOut.fold(
      fail => schemaHelper.hintResponse(request.body, schema),
      result =>
        // Legacy formatter
        Ok(Json.obj("keeps" -> Json.arr(
          Json.obj("recipients" -> Json.obj(
            "libraries" -> Json.toJson(result)
          ))
        )))
    )
  }

  private object GetKeepsByUri {
    import json.SchemaReads._
    final case class Input(url: String, recipient: Option[String], paginationContext: Option[PaginationContext[Keep]], config: KeepViewAssemblyOptions)
    val schemaReads: SchemaReads[Input] = (
      (__ \ 'url).readWithSchema[String] and
      (__ \ 'recipient).readNullableWithSchema[String] and
      (__ \ 'paginationContext).readNullableWithSchema[PaginationContext[Keep]] and
      (__ \ 'config).readNullableWithSchema(KeepInfoAssemblerConfig.useDefaultForMissing).map(_ getOrElse KeepInfoAssemblerConfig.default)
    )(Input.apply _)
    implicit val reads = schemaReads.reads
    val schema = schemaReads.schema
    val schemaHelper = json.schemaHelper(reads)
    val outputWrites: Writes[NewKeepInfosForPage] = NewKeepInfosForPage.writes
  }
  def getKeepsByUri() = UserAction.async(parse.tolerantJson) { implicit request =>
    import GetKeepsByUri._
    request.body.asOpt[Input].fold(Future.successful(schemaHelper.loudHintResponse(request.body, schema))) { input =>
      val seenIds = input.paginationContext.fold(Set.empty[Id[Keep]])(_.toSet)
      val uriIdOpt = db.readOnlyReplica { implicit s => uriInterner.getByUri(input.url).map(_.id.get) }
      val newIdsBySection = uriIdOpt.map { uriId =>
        db.readOnlyReplica { implicit s => keepRepo.getSectionedKeepsOnUri(request.userId, uriId, seenIds, limit = 10) }
      }.getOrElse(Map.empty)
      val sectionById = newIdsBySection.flatMap {
        case (section, ids) => ids.map(_ -> section)
      }
      val newIds = newIdsBySection.traverseByKey.flatten
      val result = {
        val keepInfosFut = keepInfoAssembler.assembleKeepInfos(viewer = Some(request.userId), keepSet = newIds.toSet, config = input.config)
        val pageInfoFut = uriIdOpt.fold(Future.successful(Option.empty[NewPageInfo])) { uriId =>
          keepInfoAssembler.assemblePageInfos(viewer = Some(request.userId), uriSet = Set(uriId), config = input.config).map(_.get(uriId))
        }
        for { keepInfos <- keepInfosFut; pageInfo <- pageInfoFut } yield NewKeepInfosForPage(
          page = pageInfo,
          keeps = newIds.flatMap(kId => keepInfos.get(kId).flatMap(_.getRight).map(_ -> sectionById(kId))),
          paginationContext = PaginationContext.fromSet(seenIds ++ newIds)
        )
      }
      result.map(ans => Ok(outputWrites.writes(ans)))
    }
  }
}
