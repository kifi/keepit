package com.keepit.controllers.client

import com.google.inject.Inject
import com.keepit.commanders.KeepQuery.{ ForUriAndRecipients, FirstOrder }
import com.keepit.commanders.gen.BasicLibraryGen
import com.keepit.commanders.{ KeepQuery, KeepQueryCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.core.mapExtensionOps
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.json
import com.keepit.common.json.SchemaReads
import com.keepit.common.mail.EmailAddress
import com.keepit.common.performance.Stopwatch
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time._
import com.keepit.common.util.PaginationContext
import com.keepit.common.util.RightBias._
import com.keepit.model._
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.shoebox.data.assemblers.KeepInfoAssemblerConfig.KeepViewAssemblyOptions
import com.keepit.shoebox.data.assemblers.{ KeepInfoAssembler, KeepInfoAssemblerConfig }
import com.keepit.shoebox.data.keep.{ ExternalKeepRecipient, NewKeepInfosForIntersection, NewKeepInfosForPage, NewPageInfo }
import org.apache.commons.lang3.RandomStringUtils
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
  basicUserRepo: BasicUserRepo,
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
          paging = KeepQuery.Paging(filter = None, offset = 0, limit = Math.min(limit, 50)),
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
    final case class Input(url: String, paginationContext: Option[PaginationContext[Keep]], config: KeepViewAssemblyOptions)
    val schemaReads: SchemaReads[Input] = (
      (__ \ 'url).readWithSchema[String] and
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

  private object GetKeepsByUriAndRecipients {
    import json.SchemaReads._
    final case class GetKeepsByUriAndRecipients(
      url: String,
      limit: Option[Int],
      users: Set[ExternalId[User]],
      libraries: Set[PublicId[Library]],
      emails: Set[EmailAddress],
      paginationContext: PaginationContext[Keep],
      config: KeepViewAssemblyOptions)
    val schemaReads: SchemaReads[GetKeepsByUriAndRecipients] = (
      (__ \ 'url).readWithSchema[String] and
      (__ \ 'limit).readNullableWithSchema[Int] and
      (__ \ 'users).readNullableWithSchema[Set[ExternalId[User]]].map(_ getOrElse Set.empty) and
      (__ \ 'libraries).readNullableWithSchema[Set[PublicId[Library]]].map(_ getOrElse Set.empty) and
      (__ \ 'emails).readNullableWithSchema[Set[EmailAddress]].map(_ getOrElse Set.empty) and
      (__ \ 'paginationContext).readNullableWithSchema[PaginationContext[Keep]].map(_ getOrElse PaginationContext.empty) and
      (__ \ 'config).readNullableWithSchema(KeepInfoAssemblerConfig.useDefaultForMissing).map(_ getOrElse KeepInfoAssemblerConfig.default)
    )(GetKeepsByUriAndRecipients.apply _)
    implicit val reads = schemaReads.reads
    val schema = schemaReads.schema
    val schemaHelper = json.schemaHelper(reads)
    val outputWrites: Writes[NewKeepInfosForIntersection] = NewKeepInfosForIntersection.writes
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
    } yield getKeepInfosForIntersection(request.userId, input.url, recipients, input.paginationContext, input.limit)

    resultIfEverythingChecksOut.fold(
      fail => Future.successful(schemaHelper.hintResponse(request.body, schema)),
      result => result.map(ans => Ok(outputWrites.writes(ans)))
    )
  }

  private def getKeepInfosForIntersection(viewer: Id[User], url: String, recipients: KeepRecipients, paginationContext: PaginationContext[Keep], limitOpt: Option[Int]): Future[NewKeepInfosForIntersection] = {
    val stopwatch = new Stopwatch(s"[PIC-PAGE-${RandomStringUtils.randomAlphanumeric(5)}]")
    val uriOpt = db.readOnlyReplica { implicit s =>
      uriInterner.getByUri(url).map(_.id.get)
    }
    stopwatch.logTimeWith("uri_retrieved")
    uriOpt.fold(Future.successful(NewKeepInfosForIntersection.empty)) { uriId =>
      val intersectorFut = if (recipients.numLibraries + recipients.numParticipants > 0) db.readOnlyReplicaAsync(implicit s => getIntersectedEntity(recipients)) else Future.successful(None)
      val seenKeeps = paginationContext.toSet
      val query = KeepQuery(
        target = ForUriAndRecipients(uriId, viewer, recipients),
        paging = KeepQuery.Paging(filter = Some(KeepQuery.Seen(seenKeeps)), offset = 0, limit = limitOpt.getOrElse(4)),
        arrangement = None
      )
      for {
        intersectionKeepIds <- db.readOnlyReplicaAsync { implicit s => queryCommander.getKeeps(Some(viewer), query) }
        _ = stopwatch.logTimeWith(s"query_complete_n_${intersectionKeepIds.length}")
        keepInfosFut = keepInfoAssembler.assembleKeepInfos(Some(viewer), intersectionKeepIds.toSet)

        keepInfos <- keepInfosFut
        intersector <- intersectorFut
      } yield {
        stopwatch.logTimeWith("done")
        val sortedIntersectionKeepInfos = intersectionKeepIds.flatMap(kId => keepInfos.get(kId).flatMap(_.getRight))
        NewKeepInfosForIntersection(
          PaginationContext.fromSet[Keep](seenKeeps ++ intersectionKeepIds.toSet),
          sortedIntersectionKeepInfos,
          intersector
        )
      }
    }
  }

  private def getIntersectedEntity(recipients: KeepRecipients)(implicit session: RSession): Option[ExternalKeepRecipient] = {
    import ExternalKeepRecipient._
    (recipients.users.headOption, recipients.libraries.headOption, recipients.emails.headOption) match {
      case (Some(userId), _, _) => basicUserRepo.loadActive(userId).map(UserRecipient)
      case (_, Some(libraryId), _) => basicLibraryGen.getBasicLibrary(libraryId).map(LibraryRecipient)
      case (_, _, Some(email)) => Some(EmailRecipient(email))
      case _ => None
    }
  }

}
