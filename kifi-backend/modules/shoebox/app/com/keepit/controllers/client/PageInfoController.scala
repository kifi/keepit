package com.keepit.controllers.client

import com.google.inject.Inject
import com.keepit.commanders.KeepQuery.ForUri
import com.keepit.common.json
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
import play.api.libs.json.{ Writes, JsString, Reads, Json }
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
  clock: Clock,
  implicit val airbrake: AirbrakeNotifier,
  private implicit val defaultContext: ExecutionContext,
  private implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController {

  def getKeepInfosForPage(viewer: Id[User], url: String, recipients: KeepRecipients): Future[NewKeepInfosForPage] = {
    val uriOpt = db.readOnlyReplica { implicit s =>
      uriInterner.getByUri(url).map(_.id.get)
    }
    uriOpt.fold(Future.successful(NewKeepInfosForPage.empty)) { uriId =>
      val query = KeepQuery(
        target = ForUri(uriId, recipients.plusUser(viewer)), // N.B. this `plusUser` is the only thing limiting visibility, be careful when messing around here
        paging = KeepQuery.Paging(fromId = None, offset = Offset(0), limit = Limit(10)),
        arrangement = None
      )
      for {
        keepIds <- db.readOnlyReplicaAsync { implicit s => queryCommander.getKeeps(Some(viewer), query) }
        (pageInfo, keepInfos) <- {
          // Launch these in parallel
          val pageInfoFut = keepInfoAssembler.assemblePageInfos(Some(viewer), Set(uriId)).map(_.get(uriId))
          val keepInfosFut = keepInfoAssembler.assembleKeepInfos(Some(viewer), keepIds.toSet)
          for (p <- pageInfoFut; k <- keepInfosFut) yield (p, k)
        }
      } yield {
        NewKeepInfosForPage(
          page = pageInfo,
          keeps = keepIds.flatMap(kId => keepInfos.get(kId).flatMap(_.getRight))
        )
      }
    }
  }

  private object GetKeepsByUriAndRecipients {
    final case class GetKeepsByUriAndRecipients(
      url: String,
      users: Set[ExternalId[User]],
      libraries: Set[PublicId[Library]],
      emails: Set[EmailAddress])
    implicit val inputReads: Reads[GetKeepsByUriAndRecipients] = Json.reads[GetKeepsByUriAndRecipients]
    val schema = json.schemaHelper(inputReads)
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
      fail => Future.successful(BadRequest(Json.obj("err" -> fail, "hint" -> schema.hint(request.body)))),
      result => result.map(ans => Ok(outputWrites.writes(ans)))
    )
  }
}
