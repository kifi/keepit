package com.keepit.controllers.admin

import java.util.regex.{ Pattern, PatternSyntaxException }

import com.google.inject.Inject
import com.keepit.commanders.{ KeepInterner, RawBookmarkRepresentation }
import com.keepit.common.concurrent.ChunkedResponseHelper
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicIdRegistry }
import com.keepit.common.db.Id
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.{ KeepSource, Library, Organization }
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext
import scala.util.Try

class AdminGoodiesController @Inject() (
  eliza: ElizaServiceClient,
  keepInterner: KeepInterner,
  override val userActionsHelper: UserActionsHelper,
  implicit val executionContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends AdminUserActions {

  def testRegex = AdminUserPage { implicit request =>
    Ok(views.html.admin.roverTestRegex())
  }

  def testRegexFilled(regex: String, test: Option[String] = None) = AdminUserPage { implicit request =>
    Ok(views.html.admin.roverTestRegex(Some(regex), test.map(t => List(t))))
  }

  def performRegexTest = AdminUserPage(parse.json) { implicit request =>
    val body = request.body
    try {
      val regex = (body \ "regex").as[String]
      val pattern = Pattern.compile(regex)
      val tests = (body \ "tests").as[List[String]]
      val results = tests.map { pattern.matcher(_).matches }
      Ok(Json.toJson(results))
    } catch {
      case e: PatternSyntaxException =>
        BadRequest(Json.toJson(e.toString))
    }
  }

  val warmUp = {
    // This just forces registration. Probably not necessary if they're actually being used, but hey.
    Library.publicId(Id(1))
    Organization.publicId(Id(1))
  }

  def externalIdById(name: String, id: Long) = AdminUserPage { implicit request =>
    val pubIds = PublicIdRegistry.registry.filter(_._1.toLowerCase.contains(name.toLowerCase)).map {
      case (clazz, accessor) =>
        clazz + " " + accessor.toPubId(id)
    }.mkString("\n")
    Ok(pubIds)
  }

  def externalIdByPublicId(name: String, publicId: String) = AdminUserPage { implicit request =>
    val id = PublicIdRegistry.registry.filter(_._1.toLowerCase.contains(name.toLowerCase)).map {
      case (clazz, accessor) =>
        clazz + " " + Try(accessor.toId(publicId)).toOption.getOrElse("(invalid)")
    }.mkString("\n")
    Ok(id)
  }

  private def registry: Seq[(String, String, Long)] = {
    PublicIdRegistry.registry.map {
      case (companion, accessor) =>
        val a = accessor.toPubId(1)
        val b = accessor.toId(a)

        (companion, a, b)
    }
  }

  def backfillMessageThread() = AdminUserAction(parse.tolerantJson) { request =>
    val source = KeepSource.keeper
    val threads = (request.body \ "threads").as[Seq[Long]]
    implicit val context = HeimdalContext.empty
    val enum = ChunkedResponseHelper.chunkedFuture(threads) { threadId =>
      for {
        res <- eliza.rpbGetThread(threadId)
        rawBookmark = RawBookmarkRepresentation(title = res.title, url = res.url, keptAt = Some(res.startedAt))
        internResponse = keepInterner.internRawBookmarksWithStatus(Seq(rawBookmark), res.startedBy, None, source)
        keepId = internResponse.successes.head.id.get
        _ <- eliza.rpbConnectKeep(threadId, keepId)
      } yield {
        Json.obj(
          "requested" -> threadId,
          "received" -> Json.arr(res.title, res.url, res.startedAt, res.startedBy),
          "successfully_interned" -> internResponse.successes.map(_.id.get)
        )
      }
    }
    Ok.chunked(enum)
  }
}
