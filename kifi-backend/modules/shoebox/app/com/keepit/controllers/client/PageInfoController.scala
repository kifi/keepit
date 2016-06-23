package com.keepit.controllers.client

import com.google.inject.Inject
import com.keepit.commanders.KeepQuery.{ ForUriAndRecipients, FirstOrder }
import com.keepit.commanders.gen.BasicLibraryGen
import com.keepit.commanders.{ KeepQuery, KeepQueryCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.core.mapExtensionOps
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.json
import com.keepit.common.json.SchemaReads
import com.keepit.common.performance.Stopwatch
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time._
import com.keepit.common.util.PaginationContext
import com.keepit.common.util.RightBias._
import com.keepit.model._
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.shoebox.data.assemblers.KeepInfoAssemblerConfig.KeepViewAssemblyOptions
import com.keepit.shoebox.data.assemblers.{ KeepInfoAssembler, KeepInfoAssemblerConfig }
import com.keepit.shoebox.data.keep.{ KeepRecipientId, ExternalKeepRecipientId, ExternalKeepRecipient, NewKeepInfosForIntersection, NewKeepInfosForPage, NewPageInfo }
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
  uriRepo: NormalizedURIRepo,
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
    final case class Input(url: Option[String], uriId: Option[PublicId[NormalizedURI]], paginationContext: Option[PaginationContext[Keep]], config: KeepViewAssemblyOptions, filterRecipient: Option[ExternalKeepRecipientId])
    val schemaReads: SchemaReads[Input] = (
      (__ \ 'url).readNullableWithSchema[String] and
      (__ \ 'uriId).readNullableWithSchema[String].map(_.map(PublicId[NormalizedURI])) and
      (__ \ 'paginationContext).readNullableWithSchema[PaginationContext[Keep]] and
      (__ \ 'config).readNullableWithSchema(KeepInfoAssemblerConfig.useDefaultForMissing).map(_ getOrElse KeepInfoAssemblerConfig.default) and
      (__ \ 'filterRecipient).readNullableWithSchema[ExternalKeepRecipientId]
    )(Input.apply _)
    implicit val reads = schemaReads.reads
    val schema = schemaReads.schema
    val schemaHelper = json.schemaHelper(reads)
    val outputWrites: Writes[NewKeepInfosForPage] = NewKeepInfosForPage.writes
  }
  def getKeepsByUri() = UserAction.async(parse.tolerantJson) { implicit request =>
    import GetKeepsByUri._
    request.body.asOpt[Input].fold(Future.successful(schemaHelper.hintResponse(request.body, schema))) { input =>
      val seenIds = input.paginationContext.fold(Set.empty[Id[Keep]])(_.toSet)
      val uriOpt = db.readOnlyReplica { implicit s => getNormalizedURI(input.uriId, input.url) }
      val newIdsBySection = uriOpt.map { uri =>
        val stopwatch = new Stopwatch(s"[OTP-KEEPS-${RandomStringUtils.randomAlphanumeric(5)}}] filtering=${input.filterRecipient}}")
        db.readOnlyReplica { implicit s =>
          import com.keepit.common.core._
          val recipientToFilter = input.filterRecipient.flatMap(convertExternalRecipientId)
          keepRepo.getSectionedKeepsOnUri(request.userId, uri.id.get, seenIds, limit = 10, recipientToFilter).tap { _ =>
            stopwatch.logTimeWith("fetched_keeps")
          }
        }
      }.getOrElse(Map.empty)

      val sectionById = newIdsBySection.flatMap {
        case (section, ids) => ids.map(_ -> section)
      }
      val newIds = newIdsBySection.traverseByKey.flatten
      val result = {
        val keepInfosFut = keepInfoAssembler.assembleKeepInfos(viewer = Some(request.userId), keepSet = newIds.toSet, config = input.config)
        val pageInfoFut = uriOpt.fold(Future.successful(Option.empty[NewPageInfo])) { uri =>
          keepInfoAssembler.assemblePageInfos(viewer = Some(request.userId), uriSet = Set(uri.id.get), config = input.config).map(_.get(uri.id.get))
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

  private def convertExternalRecipientId(extId: ExternalKeepRecipientId)(implicit session: RSession): Option[KeepRecipientId] = extId match {
    case ExternalKeepRecipientId.Email(emailAddress) => Some(KeepRecipientId.Email(emailAddress))
    case ExternalKeepRecipientId.UserId(externalId) => Some(KeepRecipientId.UserId(userRepo.convertExternalId(externalId)))
    case ExternalKeepRecipientId.LibraryId(publicId) => Library.decodePublicId(publicId).map(libId => KeepRecipientId.LibraryId(libId)).toOption
  }

  private def getNormalizedURI(uriIdOpt: Option[PublicId[NormalizedURI]], urlOpt: Option[String])(implicit session: RSession): Option[NormalizedURI] = {
    (uriIdOpt, urlOpt) match {
      case (Some(uriId), _) => NormalizedURI.decodePublicId(uriId).map(uriRepo.get).toOption
      case (_, Some(url)) => uriInterner.getByUri(url)
      case _ => None
    }
  }

  private object GetKeepsByUriAndRecipients {
    import json.SchemaReads._
    final case class GetKeepsByUriAndRecipients(
      url: Option[String],
      uriId: Option[PublicId[NormalizedURI]],
      intersector: Option[ExternalKeepRecipientId],
      limit: Option[Int],
      paginationContext: PaginationContext[Keep],
      config: KeepViewAssemblyOptions)
    val schemaReads: SchemaReads[GetKeepsByUriAndRecipients] = (
      (__ \ 'url).readNullableWithSchema[String] and
      (__ \ 'uriId).readNullableWithSchema[String].map(_.map(PublicId[NormalizedURI])) and
      (__ \ 'intersector).readNullableWithSchema[ExternalKeepRecipientId] and
      (__ \ 'limit).readNullableWithSchema[Int] and
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
    request.body.asOpt[GetKeepsByUriAndRecipients] match {
      case None => Future.successful(schemaHelper.hintResponse(request.body, schema))
      case Some(input) =>
        db.readOnlyReplica { implicit session =>
          getNormalizedURI(input.uriId, input.url).map(uri => (uri, input.intersector.flatMap(convertExternalRecipientId)))
        }
          .map { case (uri, intersectorId) => getKeepInfosForIntersection(request.userId, uri, intersectorId, input.paginationContext, input.limit) }
          .getOrElse(Future.successful(NewKeepInfosForIntersection.empty))
          .map(result => Ok(outputWrites.writes(result)))
    }
  }

  private def getKeepInfosForIntersection(viewer: Id[User], uri: NormalizedURI, intersectorId: Option[KeepRecipientId], paginationContext: PaginationContext[Keep], limitOpt: Option[Int]): Future[NewKeepInfosForIntersection] = {
    val seenKeeps = paginationContext.toSet
    val recipients = intersectorId.map(_.toKeepRecipients).getOrElse(KeepRecipients.EMPTY)
    val query = KeepQuery(
      target = ForUriAndRecipients(uri.id.get, viewer, recipients),
      paging = KeepQuery.Paging(filter = Some(KeepQuery.Seen(seenKeeps)), offset = 0, limit = limitOpt.getOrElse(4)),
      arrangement = None
    )
    val keepIdsFut = db.readOnlyReplicaAsync { implicit s => queryCommander.getKeeps(Some(viewer), query) }
    val intersectorFut = intersectorId.map(id => db.readOnlyMasterAsync { implicit s => getIntersectedEntity(id) }).getOrElse(Future.successful(None))
    for {
      keepIds <- keepIdsFut
      keepInfos <- keepInfoAssembler.assembleKeepInfos(Some(viewer), keepIds.toSet)
      intersectorOpt <- intersectorFut
    } yield {
      val sortedIntersectionKeepInfos = keepIds.flatMap(kId => keepInfos.get(kId).flatMap(_.getRight))
      NewKeepInfosForIntersection(
        uri.url,
        PaginationContext.fromSet[Keep](seenKeeps ++ keepIds.toSet),
        sortedIntersectionKeepInfos,
        intersectorOpt
      )
    }
  }

  private def getIntersectedEntity(recipientId: KeepRecipientId)(implicit session: RSession): Option[ExternalKeepRecipient] = {
    import KeepRecipientId._
    import ExternalKeepRecipient._
    recipientId match {
      case UserId(uid) => basicUserRepo.loadActive(uid).map(UserRecipient)
      case LibraryId(lid) => basicLibraryGen.getBasicLibrary(lid).map(LibraryRecipient)
      case Email(address) => Some(EmailRecipient(address))
    }
  }

  private object GetPageInfo {
    import json.SchemaReads._
    final case class GetPageInfo(url: Option[String], uriId: Option[PublicId[NormalizedURI]], config: KeepViewAssemblyOptions)
    val schemaReads: SchemaReads[GetPageInfo] = (
      (__ \ 'url).readNullableWithSchema[String] and
      (__ \ 'uriId).readNullableWithSchema[String].map(_.map(PublicId[NormalizedURI])) and
      (__ \ 'config).readNullableWithSchema(KeepInfoAssemblerConfig.useDefaultForMissing).map(_ getOrElse KeepInfoAssemblerConfig.default)
    )(GetPageInfo.apply _)
    implicit val reads = schemaReads.reads
    val schema = schemaReads.schema
    val schemaHelper = json.schemaHelper(reads)
  }

  def getPageInfo = UserAction.async(parse.tolerantJson) { implicit request =>
    import GetPageInfo._
    request.body.asOpt[GetPageInfo] match {
      case None => Future.successful(schemaHelper.hintResponse(request.body, schema))
      case Some(input) =>
        val uriOpt = db.readOnlyReplica(implicit s => getNormalizedURI(input.uriId, input.url))
        uriOpt.map { uri =>
          val uriId = uri.id.get
          keepInfoAssembler.assemblePageInfos(Some(request.userId), Set(uriId), input.config)
            .map(_.get(uriId))
            .map(page => Ok(Json.obj("page" -> page)))
        }.getOrElse(Future.successful(NotFound))
    }
  }
}
