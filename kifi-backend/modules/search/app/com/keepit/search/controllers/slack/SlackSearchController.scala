package com.keepit.search.controllers.slack

import com.google.inject.Inject
import com.keepit.commanders.ProcessedImageSize
import com.keepit.common.controller.{ SearchServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ DirectUrl, HttpClient }
import com.keepit.common.store.S3ImageConfig
import com.keepit.model.{ Keep, NormalizedURI }
import com.keepit.model.UserExperimentType._
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.model.{ BasicImages, RoverUriSummary }
import com.keepit.search.controllers.util.SearchControllerUtil
import com.keepit.search._
import com.keepit.search.index.graph.keep.{ KeepFields }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.slack.models.{ SlackAttachment, SlackCommandResponse, SlackCommand, SlackCommandRequest }
import com.keepit.common.core._
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }

object SlackSearchController {
  val maxUris = 5
  val idealImageSize = ProcessedImageSize.Small.idealSize
}

class SlackSearchController @Inject() (
    val userActionsHelper: UserActionsHelper,
    val shoeboxClient: ShoeboxServiceClient,
    rover: RoverServiceClient,
    uriSearchCommander: UriSearchCommander,
    httpClient: HttpClient,
    airbrake: AirbrakeNotifier,
    implicit val imageConfig: S3ImageConfig,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val ec: ExecutionContext) extends UserActions with SearchServiceController with SearchControllerUtil with Logging {

  import SlackSearchController._

  def search() = MaybeUserAction.async(parse.tolerantJson) { request =>
    request.body.asOpt[SlackCommandRequest] match {
      case Some(command) if command.command == SlackCommand.Kifi =>
        shoeboxClient.getIntegrationsBySlackChannel(command.token, command.teamId, command.channelId).flatMap {
          case Some(integrations) =>
            val futureResponse = {
              import SlackAttachment._
              import SlackCommandResponse._
              val futureTextAndAttachments: Future[(String, Seq[SlackAttachment])] = integrations.filter(_.on).maxByOpt(_.lastMessageTimestamp) match {
                case Some(integration) if integration.lastMessageTimestamp.isDefined =>
                  val acceptLangs = getAcceptLangs(request)
                  val (userId, experiments) = getUserAndExperiments(request)
                  val libraryScope = LibraryScope(integration.libraryId, authorized = true)
                  val sourceScope = SourceScope(KeepFields.Source.apply(command.channelId))
                  val searchFilter = SearchFilter(None, None, Some(libraryScope), None, Some(sourceScope))
                  val searchContext = SearchContext(None, SearchRanking.relevancy, searchFilter, disablePrefixSearch = true, disableFullTextSearch = false)
                  uriSearchCommander.searchUris(userId, acceptLangs, experiments, command.text, Future.successful(searchContext), maxUris, None, None, None).flatMap { uriSearchResult =>
                    val relevantHits = uriSearchResult.hits.take(uriSearchResult.cutPoint)

                    val uriIds = relevantHits.map(hit => Id[NormalizedURI](hit.id))
                    val futureUriSummaries: Future[Map[Id[NormalizedURI], RoverUriSummary]] = rover.getUriSummaryByUris(uriIds.toSet)
                    val futureKeepImages: Future[Map[Id[Keep], BasicImages]] = {
                      val keepIds = relevantHits.flatMap(hit => hit.keepId.map(Id[Keep](_)))
                      shoeboxClient.getKeepImages(keepIds.toSet)
                    }

                    for {
                      summaries <- futureUriSummaries
                      keepImages <- futureKeepImages
                    } yield {
                      val attachments = relevantHits.map { hit =>
                        val uriId = Id[NormalizedURI](hit.id)
                        val title = hit.title
                        val url = hit.url
                        val summary = summaries.get(uriId)
                        val keepId = hit.keepId.map(Id[Keep](_))
                        val imageOpt = (keepId.flatMap(keepImages.get) orElse summary.map(_.images)).flatMap(_.get(idealImageSize))
                        SlackAttachment.fromTitleAndImage(Title(title, Some(url)), thumbUrl = imageOpt.map(_.path.getUrl), color = "good")
                      }
                      val text = {
                        if (relevantHits.isEmpty) s"We couldn't find any link relevant to your query in this channel :("
                        else s"Here are the ${relevantHits.length} most relevant links we found in ${command.channelName.value}:"
                      }
                      (text, attachments)
                    }
                  }
                case Some(emptyIntegration) =>
                  val text = s"We haven't captured any link from this channel yet. Messages whose links have been captured will be marked with :heavy_check_mark:!"
                  Future.successful((text, Seq.empty))
                case None =>
                  shoeboxClient.getBasicLibraryDetails(integrations.map(_.libraryId).toSet, idealImageSize = idealImageSize, None).map { libraryInfos =>
                    val text = s"We are not capturing links from this channel yet, all Kifi integrations are turned off. Turn one on:"
                    val attachments = libraryInfos.values.toSeq.map { info =>
                      SlackAttachment.fromTitleAndImage(Title(info.name, Some(info.url.absolute)), thumbUrl = info.imageUrl, color = "warning")
                    }
                    (text, attachments)
                  }
              }
              futureTextAndAttachments.imap { case (text, attachments) => SlackCommandResponse(ResponseType.Ephemeral, text, attachments) }
              // httpClient.postFuture(DirectUrl(command.responseUrl), Json.toJson(response)) todo(Léo): call Slack back
            }
            futureResponse.map(r => Ok(Json.toJson(r))) // for testing
          case None => Future.successful(BadRequest("invalid_auth"))
        }
      case _ => Future.successful(BadRequest("invalid_command"))
    }
  }
}
