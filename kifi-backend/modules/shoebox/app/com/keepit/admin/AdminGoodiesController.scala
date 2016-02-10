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

  def fixSlackAttributions(from: Option[Long], limit: Int, n: Int) = AdminUserAction(parse.tolerantJson) { implicit request =>
    val response: Enumerator[String] = Concurrent.unicast(onStart = { (channel: Concurrent.Channel[String]) =>
      FutureHelpers.foldLeftUntil(Seq.fill(n)(Unit))(from.map(Id[KeepSourceAttribution])) { (fromIdOpt, _) =>
        val attrsToFix = db.readOnlyMaster { implicit s =>
          keepSourceAttributionRepo.adminGetFromId(fromIdOpt, limit)
        }
        attrsToFix.foreach { attr =>
          attr.attribution match {
            case RawSlackAttribution(msg, None) =>
              db.readWrite { implicit s =>
                slackChannelRepo.adminGetChannel(msg.channel.id) match {
                  case None => channel.push(s"Could not find ${msg.channel.id} in the channel repo (attr id ${attr.id.get})")
                  case Some(ch) =>
                    keepSourceAttributionRepo.save(attr.copy(attribution = RawSlackAttribution(msg, Some(ch.slackTeamId))))
                    channel.push(s"Fixed attr ${attr.id.get} from channel ${msg.channel.id} in team ${ch.slackTeamId}")
                }
              }
            case _ => // nothing to do
          }
        }
        Future.successful((attrsToFix.map(_.id.get).maxOpt, attrsToFix.isEmpty))
      } andThen {
        case res =>
          if (res.isFailure) channel.push("server error")
          if (res.isSuccess) channel.push("Finished!")
          channel.eofAndEnd()
      }
    })
    Ok.chunked(response)
  }
}
