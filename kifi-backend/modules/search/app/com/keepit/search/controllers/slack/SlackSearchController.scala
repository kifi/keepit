package com.keepit.search.controllers.slack

import com.google.inject.Inject
import com.keepit.commanders.ProcessedImageSize
import com.keepit.common.controller.{ SearchServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.domain.DomainToNameMapper
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ DirectUrl, HttpClient }
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.util.LinkElement
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.model.{ BasicImages, RoverUriSummary }
import com.keepit.search.controllers.util.SearchControllerUtil
import com.keepit.search._
import com.keepit.search.index.graph.keep.{ KeepFields }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.slack.models._
import com.keepit.common.core._
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Success

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

  def search() = MaybeUserAction.async(parse.tolerantFormUrlEncoded) { request =>
    SlackCommandRequest.fromSlack(request.body) match {
      case Success(command) if command.command == SlackCommand.Kifi && command.token == KifiSlackApp.SLACK_COMMAND_TOKEN =>
        shoeboxClient.getIntegrationsBySlackChannel(command.teamId, command.channelId).flatMap {
          case integrations =>
            import SlackAttachment._
            import SlackCommandResponse._
            val futureLibraries = shoeboxClient.getBasicLibraryDetails(integrations.allLibraries, idealImageSize, None)
            val futureTextAndAttachments: Future[(String, Seq[SlackAttachment])] = {
              if (integrations.allLibraries.nonEmpty) {
                val acceptLangs = getAcceptLangs(request)
                val (userId, experiments) = getUserAndExperiments(request)
                val libraryScope = LibraryScope(integrations.allLibraries, authorized = true)
                val sourceScope = SourceScope(KeepFields.Source.apply(command.channelId))
                val searchFilter = SearchFilter(None, None, Some(libraryScope), None, Some(sourceScope))
                val searchContext = SearchContext(None, SearchRanking.relevancy, searchFilter, disablePrefixSearch = true, disableFullTextSearch = false)
                uriSearchCommander.searchUris(userId, acceptLangs, experiments, command.text, Future.successful(searchContext), maxUris, None, None, None).flatMap { uriSearchResult =>
                  val relevantHits = uriSearchResult.hits.take(uriSearchResult.cutPoint)

                  val uriIds = relevantHits.map(hit => Id[NormalizedURI](hit.id))
                  val keepIds = relevantHits.flatMap(hit => hit.keepId.map(Id[Keep](_)))
                  val futureUriSummaries: Future[Map[Id[NormalizedURI], RoverUriSummary]] = rover.getUriSummaryByUris(uriIds.toSet)
                  val futureKeepImages: Future[Map[Id[Keep], BasicImages]] = shoeboxClient.getKeepImages(keepIds.toSet)
                  val futureSourceAttributions: Future[Map[Id[Keep], SourceAttribution]] = shoeboxClient.getSourceAttributionForKeeps(keepIds.toSet)

                  for {
                    summaries <- futureUriSummaries
                    keepImages <- futureKeepImages
                    sourceAttributions <- futureSourceAttributions
                    libraries <- futureLibraries
                  } yield {
                    val attachments = relevantHits.map { hit =>
                      val url = hit.url
                      val uriId = Id[NormalizedURI](hit.id)
                      val summary = summaries.get(uriId)
                      val title = Some(hit.title).filter(_.nonEmpty) orElse summary.flatMap(_.article.title.filter(_.nonEmpty)) getOrElse url
                      val keepId = hit.keepId.map(Id[Keep](_))
                      val imageOpt = (keepId.flatMap(keepImages.get) orElse summary.map(_.images)).flatMap(_.get(idealImageSize))
                      val pretext = {
                        import com.keepit.common.util.{ DescriptionElements => Elements }
                        import com.keepit.common.util.DescriptionElements._
                        val attribution = keepId.flatMap(sourceAttributions.get).map {
                          case twitter: TwitterAttribution => Elements("via", twitter.screenName.value --> LinkElement(twitter.getOriginalURL))
                          case SlackAttribution(message) => Elements("via", message.username.value, "in", message.channel.name.value, "·", message.timestamp.toDateTime --> LinkElement(message.permalink))
                        }
                        val library = hit.libraryId.flatMap(id => libraries.get(Id(id)))
                        val domain = DomainToNameMapper.getNameFromUrl(url)
                        Elements.formatForSlack(Elements(domain, library.map(lib => Elements("kept in", lib.name --> LinkElement(lib.url))), attribution))
                      }
                      SlackAttachment(
                        pretext = Some(pretext),
                        title = Some(Title(title, Some(url))),
                        text = summary.flatMap(_.article.description),
                        thumbUrl = imageOpt.map("https:" + _.path.getUrl),
                        color = Some("good")
                      )
                    }
                    val text = {
                      if (relevantHits.isEmpty) s"We couldn't find any relevant link for '${command.text}' in this channel :("
                      else s"Top links for '${command.text}' in this channel:"
                    }
                    (text, attachments)
                  }
                }
              } else {
                val text = "You don't have any integration with this channel, add an one maybe?"
                Future.successful((text, Seq.empty))
              }
            }
            futureTextAndAttachments.imap {
              case (text, attachments) =>
                val response = Json.toJson(SlackCommandResponse(ResponseType.Ephemeral, text, attachments))
                Ok(response)
            }
        }
      case invalidCommand =>
        airbrake.notify(s"Invalid Slack command: $invalidCommand")
        Future.successful(BadRequest("invalid_command"))
    }
  }
}
