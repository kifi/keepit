package com.keepit.search.controllers.slack

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ SearchServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ DirectUrl, HttpClient }
import com.keepit.model.UserExperimentType._
import com.keepit.search.controllers.util.SearchControllerUtil
import com.keepit.search._
import com.keepit.search.index.graph.keep.{ KeepFields }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.slack.models.{ SlackAttachment, SlackCommandResponse, SlackCommand, SlackCommandRequest }
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }

object SlackSearchController {
  val maxUris = 5
}

class SlackSearchController @Inject() (
    val userActionsHelper: UserActionsHelper,
    val shoeboxClient: ShoeboxServiceClient,
    uriSearchCommander: UriSearchCommander,
    httpClient: HttpClient,
    airbrake: AirbrakeNotifier,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val ec: ExecutionContext) extends UserActions with SearchServiceController with SearchControllerUtil with Logging {

  import SlackSearchController._

  def search() = MaybeUserAction.async(parse.tolerantJson) { request =>
    request.body.asOpt[SlackCommandRequest] match {
      case Some(command) if command.command == SlackCommand.Kifi => shoeboxClient.getSlackChannelLibrary(command.token, command.teamId, command.channelId).map {
        case Some(libraryId) =>
          SafeFuture {
            val acceptLangs = getAcceptLangs(request)
            val (userId, experiments) = getUserAndExperiments(request)
            val libraryScope = LibraryScope(libraryId, authorized = true)
            val sourceScope = SourceScope(KeepFields.Source.apply(command.channelId))
            val searchFilter = SearchFilter(None, None, Some(libraryScope), None, Some(sourceScope))
            val searchContext = SearchContext(None, SearchRanking.relevancy, searchFilter, disablePrefixSearch = true, disableFullTextSearch = false)
            uriSearchCommander.searchUris(userId, acceptLangs, experiments, command.text, Future.successful(searchContext), maxUris, None, None, None).flatMap { uriSearchResult =>

              val attachments = uriSearchResult.hits.take(uriSearchResult.cutPoint).map { hit =>
                import SlackAttachment._
                SlackAttachment.fromTitle(Title(hit.title, Some(hit.url))) // todo(Léo): enhance with attribution, images?
              }

              val response = {
                import SlackCommandResponse._
                val text = {
                  if (uriSearchResult.hits.isEmpty || uriSearchResult.cutPoint == 0) s"We couldn't find any link relevant to your query in this channel :("
                  else s"Here are the ${uriSearchResult.cutPoint} most relevant links we found in ${command.channelName.value}:"
                }
                SlackCommandResponse(ResponseType.Ephemeral, text, attachments)
              }

              httpClient.postFuture(DirectUrl(command.responseUrl), Json.toJson(response))
            }
          }
          Ok("Fluctuat Nec Mergitur")

        case None => BadRequest("invalid_auth")
      }
      case _ => Future.successful(BadRequest("invalid_command"))
    }
  }
}
