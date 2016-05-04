package com.keepit.controllers.client

import com.google.inject.Inject
import com.keepit.commanders.KeepQuery.{ FirstOrder, ForUri }
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
import com.keepit.shoebox.data.assemblers.KeepInfoAssembler
import com.keepit.shoebox.data.keep.NewKeepInfosForPage
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
          paging = KeepQuery.Paging(fromId = None, offset = Offset(0), limit = Limit(limit)),
          arrangement = None
        )
        val keepIds = keepRepo.getKeepIdsForQuery(query).sorted(implicitly[Ordering[Id[Keep]]].reverse).take(50).toSet
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

  private def getKeepInfosForPage(viewer: Id[User], url: String, recipients: KeepRecipients): Future[NewKeepInfosForPage] = {
    val stopwatch = new Stopwatch(s"[PIC-PAGE-${RandomStringUtils.randomAlphanumeric(5)}]")
    val uriOpt = db.readOnlyReplica { implicit s =>
      uriInterner.getByUri(url).map(_.id.get)
    }
    stopwatch.logTimeWith("uri_retrieved")
    uriOpt.fold(Future.successful(NewKeepInfosForPage.empty)) { uriId =>
      val query = KeepQuery(
        target = ForUri(uriId, viewer, recipients),
        paging = KeepQuery.Paging(fromId = None, offset = Offset(0), limit = Limit(10)),
        arrangement = None
      )
      for {
        keepIds <- db.readOnlyReplicaAsync { implicit s => queryCommander.getKeeps(Some(viewer), query) }
        _ = stopwatch.logTimeWith(s"query_complete_n_${keepIds.length}")
        (pageInfo, keepInfos) <- {
          // Launch these in parallel
          val pageInfoFut = keepInfoAssembler.assemblePageInfos(Some(viewer), Set(uriId)).map(_.get(uriId))
          val keepInfosFut = keepInfoAssembler.assembleKeepInfos(Some(viewer), keepIds.toSet)
          for (p <- pageInfoFut; k <- keepInfosFut) yield (p, k)
        }
      } yield {
        stopwatch.logTimeWith("done")
        NewKeepInfosForPage(
          page = pageInfo,
          keeps = keepIds.flatMap(kId => keepInfos.get(kId).flatMap(_.getRight))
        )
      }
    }
  }

  private object GetKeepsByUriAndRecipients {
    import json.SchemaReads._
    final case class GetKeepsByUriAndRecipients(
      url: String,
      users: Set[ExternalId[User]],
      libraries: Set[PublicId[Library]],
      emails: Set[EmailAddress])
    val schemaReads: SchemaReads[GetKeepsByUriAndRecipients] = (
      (__ \ 'url).readWithSchema[String] and
      (__ \ 'users).readNullableWithSchema[Set[ExternalId[User]]].map(_ getOrElse Set.empty) and
      (__ \ 'libraries).readNullableWithSchema[Set[PublicId[Library]]].map(_ getOrElse Set.empty) and
      (__ \ 'emails).readNullableWithSchema[Set[EmailAddress]].map(_ getOrElse Set.empty)
    )(GetKeepsByUriAndRecipients.apply _)
    implicit val reads = schemaReads.reads
    val schema = schemaReads.schema
    val schemaHelper = json.schemaHelper(reads)
    val outputWrites: Writes[NewKeepInfosForPage] = NewKeepInfosForPage.writes
  }
  def getKeepsByUriAndRecipients() = UserAction.async(parse.tolerantJson) { implicit request =>
    import GetKeepsByUriAndRecipients._
    val resultIfEverythingChecksOut = for {
      input <- request.body.asOpt[GetKeepsByUriAndRecipients].withLeft("malformed_input")
      recipients <- db.readOnlyReplica { implicit s =>
        val userIdMap = userRepo.convertExternalIds(input.users)
        for {
          users <- input.users.fragileMap(id => userIdMap.get(id).withLeft("invalid_user_id"))
          libraries <- input.libraries.fragileMap(pubId => Library.decodePublicId(pubId).toOption.withLeft("invalid_library_id"))
        } yield KeepRecipients(libraries, input.emails, users)
      }
    } yield getKeepInfosForPage(request.userId, input.url, KeepRecipients.EMPTY)

    resultIfEverythingChecksOut.fold(
      fail => Future.successful(schemaHelper.hintResponse(request.body, schema)),
      result => result.map { ans =>
        if ((request.body \ "dryRun").asOpt[Boolean].getOrElse(false)) Ok(Json.obj("ok" -> true, "note" -> "dryRun"))
        else Ok(outputWrites.writes(ans))
      }
    )
  }
}
