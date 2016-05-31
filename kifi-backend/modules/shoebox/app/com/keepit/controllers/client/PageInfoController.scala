package com.keepit.controllers.client

import com.google.inject.Inject
import com.keepit.commanders.KeepQuery.{ Paging, FirstOrder, ForUri }
import com.keepit.commanders.gen.BasicLibraryGen
import com.keepit.common.json
import com.keepit.common.core.anyExtensionOps
import com.keepit.common.json.SchemaReads
import com.keepit.common.performance.Stopwatch
import com.keepit.common.util.RightBias
import com.keepit.common.util.RightBias._
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
import com.keepit.shoebox.data.assemblers.{ KeepInfoAssemblerConfig, KeepInfoAssembler }
import com.keepit.shoebox.data.assemblers.KeepInfoAssemblerConfig.KeepViewAssemblyOptions
import com.keepit.shoebox.data.keep.{ PaginationContext, NewKeepInfosForPage }
import com.kifi.macros.json
import org.apache.commons.lang3.RandomStringUtils
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.mvc.Result

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
    final case class Input(url: String, paginationContext: Option[PaginationContext], config: KeepViewAssemblyOptions)
    val schemaReads: SchemaReads[Input] = (
      (__ \ 'url).readWithSchema[String] and
      (__ \ 'paginationContext).readNullableWithSchema[PaginationContext] and
      (__ \ 'config).readNullableWithSchema(KeepInfoAssemblerConfig.useDefaultForMissing).map(_ getOrElse KeepInfoAssemblerConfig.default)
    )(Input.apply _)
    implicit val reads = schemaReads.reads
    val schema = schemaReads.schema
    val schemaHelper = json.schemaHelper(reads)
    val outputWrites: Writes[NewKeepInfosForPage] = NewKeepInfosForPage.writes
  }
  def getKeepsByUri() = UserAction.async(parse.tolerantJson) { implicit request =>
    import GetKeepsByUri._
    request.body.asOpt[Input] match {
      case None => Future.successful(schemaHelper.loudHintResponse(request.body, schema))
      case Some(input) =>
        db.readOnlyReplica { implicit s => uriInterner.getByUri(input.url).map(_.id.get) } match {
          case None => Future.successful(Ok(outputWrites.writes(NewKeepInfosForPage.empty)))
          case Some(uriId) =>
            val result = for {
              keepIds <- db.readOnlyReplicaAsync { implicit s =>
                queryCommander.getKeeps(
                  requester = Some(request.userId),
                  query = KeepQuery(
                    target = ForUri(uriId, viewer = request.userId, KeepRecipients.EMPTY),
                    arrangement = None,
                    paging = Paging(filter = input.paginationContext.map(pc => KeepQuery.Seen(pc.toSet)), offset = 0, limit = 10)
                  )
                )
              }
              (keepInfos, pageInfo) <- {
                val keepInfosFut = keepInfoAssembler.assembleKeepInfos(viewer = Some(request.userId), keepSet = keepIds.toSet, config = input.config)
                val pageInfoFut = keepInfoAssembler.assemblePageInfos(viewer = Some(request.userId), uriSet = Set(uriId), config = input.config).map(_.get(uriId))
                for { k <- keepInfosFut; p <- pageInfoFut } yield (k, p)
              }
            } yield NewKeepInfosForPage(
              page = pageInfo,
              keeps = keepIds.flatMap(kId => keepInfos.get(kId).flatMap(_.getRight))
            )
            result.map(ans => Ok(outputWrites.writes(ans)))
        }
    }
  }
}
