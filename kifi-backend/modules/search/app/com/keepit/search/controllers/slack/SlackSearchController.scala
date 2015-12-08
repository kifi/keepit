package com.keepit.search.controllers.slack

import com.google.inject.Inject
import com.keepit.commanders.ProcessedImageSize
import com.keepit.common.controller.{ MaybeUserRequest, SearchServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.domain.DomainToNameMapper
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.HttpClient
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.util.LinkElement
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.model.{ BasicImages, RoverUriSummary }
import com.keepit.search.controllers.util.SearchControllerUtil
import com.keepit.search._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.slack.models.SlackCommandResponse.ResponseType
import com.keepit.slack.models._
import com.keepit.common.core._
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Success

object SlackSearchController {
  val maxUris = 5
  val idealImageSize = ProcessedImageSize.Small.idealSize

  object CommandText {
    val help = """^(?:|-h|(?:--|—)help)$""".r
    val search = """^(.+)$""".r
  }

  val supportLink = LinkElement("http://support.kifi.com/hc/en-us/articles/205409125-Integrating-Kifi-with-Slack")
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
  import com.keepit.common.util.{ DescriptionElements => Elements }
  import com.keepit.common.util.DescriptionElements._

  def search() = MaybeUserAction.async(parse.tolerantFormUrlEncoded) { request =>
    SlackCommandRequest.fromSlack(request.body) match {
      case Success(command) if command.command == SlackCommand.Kifi && command.token == KifiSlackApp.SLACK_COMMAND_TOKEN =>
        val futureResponse = shoeboxClient.getIntegrationsBySlackChannel(command.teamId, command.channelId).flatMap {
          case integrations if integrations.allLibraries.isEmpty => doHelp(command.channelName, integrations)
          case integrations =>
            command.text match {
              case CommandText.help() => doHelp(command.channelName, integrations)
              case CommandText.search(query) => doSearch(request, integrations, query)
            }
        }
        futureResponse.imap(response => Ok(Json.toJson(response)))
    }
  }

  private def doHelp(channelName: SlackChannelName, integrations: SlackChannelIntegrations): Future[SlackCommandResponse] = {
    val futureElements: Future[Elements] = {
      if (integrations.allLibraries.isEmpty) Future.successful {
        Elements.unlines(Seq(s"You don't have any Kifi library connected with #${channelName.value} yet.", "Add one maybe?" --> supportLink))
      }
      else {
        val futureLibraries = shoeboxClient.getBasicLibraryDetails(integrations.allLibraries, idealImageSize, None)
        val futureUsers = shoeboxClient.getBasicUsers(integrations.spaces.values.flatten.collect { case UserSpace(userId) => userId }.toSeq)
        val futureOrgs = shoeboxClient.getBasicOrganizationsByIds(integrations.spaces.values.flatten.collect { case OrganizationSpace(orgId) => orgId }.toSet)
        for {
          libraries <- futureLibraries
          users <- futureUsers
          orgs <- futureOrgs
        } yield {
          def listLibraries(ids: Set[Id[Library]]): Elements = Elements.unlines(ids.flatMap(id => libraries.get(id).map(id -> _)).toSeq.sortBy(_._2.name).map {
            case (libId, lib) =>
              val spaces = integrations.spaces.get(libId).map { spaces =>
                val spaceElements = Elements(spaces.map {
                  case UserSpace(userId) => users.get(userId): Elements
                  case OrganizationSpace(orgId) => orgs.get(orgId): Elements
                }.toSeq: _*)
                Elements("(", Elements.mkElements(spaceElements.flatten.sortBy(_.text), ","), ")")
              }
              Elements(lib.name --> LinkElement(lib.url), spaces)
          })
          val allLibraries: Elements = Elements(
            s"The following Kifi libraries are connected with #${channelName.value}, use */kifi* to search them:", "\n",
            "```", listLibraries(integrations.allLibraries), "```"
          )

          val fromLibraries: Elements = if (integrations.fromLibraries.isEmpty) s"No library is pushing keeps to #${channelName.value}." else Elements(
            s"Keeps from the following libraries are pushed to #${channelName.value}:", "\n",
            "```", listLibraries(integrations.fromLibraries), "```"
          )

          val toLibraries: Elements = if (integrations.toLibraries.isEmpty) s"Links posted in #${channelName.value} are not being kept in any library." else Elements(
            s"Links posted in #${channelName.value} are kept in the following libraries:", "\n",
            "```", listLibraries(integrations.toLibraries), "```"
          )

          val moreHelp = Elements(s"Learn more about Kifi and Slack", "here" --> supportLink, ".")
          Elements.mkElements(Seq(allLibraries, fromLibraries, toLibraries, moreHelp), "\n\n")
        }
      }
    }
    futureElements.imap { elements =>
      SlackCommandResponse(ResponseType.Ephemeral, Elements.formatForSlack(elements), Seq.empty)
    }
  }

  private def doSearch(request: MaybeUserRequest[_], integrations: SlackChannelIntegrations, query: String): Future[SlackCommandResponse] = {
    val futureLibraries = shoeboxClient.getBasicLibraryDetails(integrations.allLibraries, idealImageSize, None)
    val acceptLangs = getAcceptLangs(request)
    val (userId, experiments) = getUserAndExperiments(request)
    val libraryScope = LibraryScope(integrations.allLibraries, authorized = true)
    val searchFilter = SearchFilter(None, None, Some(libraryScope), None, None)
    val searchContext = SearchContext(None, SearchRanking.relevancy, searchFilter, disablePrefixSearch = true, disableFullTextSearch = false)
    uriSearchCommander.searchUris(userId, acceptLangs, experiments, query, Future.successful(searchContext), maxUris, None, None, None).flatMap { uriSearchResult =>
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
            val attribution = keepId.flatMap(sourceAttributions.get).map {
              case TwitterAttribution(tweet) => Elements("via", "@" + tweet.user.screenName.value --> LinkElement(tweet.getUrl), "on Twitter")
              case partialTwitter: PartialTwitterAttribution => Elements("via", "@" + partialTwitter.screenName.value --> LinkElement(partialTwitter.getOriginalURL), "on Twitter")
              case SlackAttribution(message) => Elements("via", "@" + message.username.value, "in", "#" + message.channel.name.value, "·", message.timestamp.toDateTime --> LinkElement(message.permalink))
            }
            val library = hit.libraryId.flatMap(id => libraries.get(Id(id)))
            val domain = DomainToNameMapper.getNameFromUrl(url)
            Elements.formatForSlack(Elements(domain, library.map(lib => Elements("kept in", lib.name --> LinkElement(lib.url))), attribution))
          }
          SlackAttachment(
            pretext = Some(pretext),
            title = Some(SlackAttachment.Title(title, Some(url))),
            text = summary.flatMap(_.article.description),
            thumbUrl = imageOpt.map("https:" + _.path.getUrl),
            color = Some("good")
          )
        }
        val text = {
          if (relevantHits.isEmpty) s"We couldn't find any relevant link for '$query' :("
          else s"Top links for '$query':"
        }
        SlackCommandResponse(ResponseType.Ephemeral, text, attachments)
      }
    }
  }
}
