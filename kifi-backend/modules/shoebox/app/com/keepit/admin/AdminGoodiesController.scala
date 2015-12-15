package com.keepit.controllers.admin

import java.util.regex.{ Pattern, PatternSyntaxException }

import com.google.inject.Inject
import com.keepit.commanders.{ KeepInterner, RawBookmarkRepresentation }
import com.keepit.common.concurrent.{ FutureHelpers, ChunkedResponseHelper }
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicIdRegistry }
import com.keepit.common.db.Id
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.{ KeepSource, Library, Organization }
import com.keepit.slack.models.SlackMessageRequest
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import play.api.libs.json.{ JsString, Json }

import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success, Try }

class AdminGoodiesController @Inject() (
  eliza: ElizaServiceClient,
  keepInterner: KeepInterner,
  inhouseSlackClient: InhouseSlackClient,
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
    val source = KeepSource.discussion
    val n = (request.body \ "n").as[Int]
    val limit = (request.body \ "limit").as[Int]
    implicit val context = HeimdalContext.empty
    FutureHelpers.sequentialExec(1 to n) { _ =>
      for {
        res <- eliza.rpbGetThreads(limit)
        keepsByThreadId = res.threads.map {
          case (threadId, to) =>
            val rawBookmark = RawBookmarkRepresentation(title = to.title, url = to.url, keptAt = Some(to.startedAt))
            val internResponse = keepInterner.internRawBookmarksWithStatus(Seq(rawBookmark), to.startedBy, None, source)
            val keepId = internResponse.successes.head.id.get
            threadId -> keepId
        }
        _ <- eliza.rpbConnectKeeps(keepsByThreadId)
      } yield {
        inhouseSlackClient.sendToSlack(InhouseSlackChannel.TEST_RYAN, SlackMessageRequest.fromKifi(s"Interned (thread, keep) pairs: $keepsByThreadId"))
      }
    }.andThen {
      case Success(_) =>
        inhouseSlackClient.sendToSlack(InhouseSlackChannel.TEST_RYAN, SlackMessageRequest.fromKifi(s"Done!"))
      case Failure(fail) =>
        inhouseSlackClient.sendToSlack(InhouseSlackChannel.TEST_RYAN, SlackMessageRequest.fromKifi(s"Crap, we broke because $fail"))
    }
    Ok(JsString("started, check #test-ryan"))
  }
}
