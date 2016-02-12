package com.keepit.controllers.admin

import java.util.regex.{ Pattern, PatternSyntaxException }

import com.google.inject.Inject
import com.keepit.common.core.traversableOnceExtensionOps
import com.keepit.commanders.{ KeepToUserCommander, RawBookmarkRepresentation, KeepInterner, KeepCommander }
import com.keepit.common.concurrent.{ ChunkedResponseHelper, FutureHelpers }
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicIdRegistry }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.slack.models.{ SlackChannelRepo, SlackMessageRequest }
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import play.api.libs.iteratee.{ Enumerator, Concurrent }
import play.api.libs.json.{ JsString, Json }

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Failure, Success, Try }

class AdminGoodiesController @Inject() (
  eliza: ElizaServiceClient,
  keepCommander: KeepCommander,
  ktuCommander: KeepToUserCommander,
  ktuRepo: KeepToUserRepo,
  keepInterner: KeepInterner,
  db: Database,
  inhouseSlackClient: InhouseSlackClient,
  keepSourceAttributionRepo: KeepSourceAttributionRepo,
  keepRepo: KeepRepo,
  slackChannelRepo: SlackChannelRepo,
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
  def fixAuthors() = AdminUserAction(parse.tolerantJson) { implicit request =>
    val usersToClear = (request.body \ "users").as[Set[Id[User]]]
    if (!(request.body \ "doIt").asOpt[Boolean].contains(true)) Ok(s"Parsed $usersToClear, if you want to go ahead include doIt: true")
    else {
      val response: Enumerator[String] = Concurrent.unicast(onStart = { (channel: Concurrent.Channel[String]) =>
        channel.push(s"Clearing the user id from slack keeps for ${usersToClear.size} users\n")
        FutureHelpers.sequentialExec(usersToClear) { user =>
          channel.push(s"Clearing user $user\n")
          db.readWriteAsync { implicit s =>
            val userKeeps = keepRepo.getByUser(user)
            val userSlackKeeps = userKeeps.filter(_.source == KeepSource.slack)
            val keepAttributions = keepSourceAttributionRepo.getByKeepIds(userSlackKeeps.map(_.id.get).toSet)
            val fixedKeeps = userSlackKeeps.filter(k => keepAttributions.contains(k.id.get)).map { keep =>
              keepRepo.save(keep.copy(userId = None))
            }
            (userKeeps.length, userSlackKeeps.length, fixedKeeps.length)
          }.andThen {
            case Success((total, slack, fixed)) =>
              channel.push(s"User $user has $total total keeps and $slack from slack. We fixed $fixed of them with attributions\n")
          }
        }.andThen {
          case res =>
            if (res.isFailure) channel.push("server error\n")
            if (res.isSuccess) channel.push("Finished!\n")
            channel.eofAndEnd()
        }
      })
      Ok.chunked(response)
    }
  }
}
