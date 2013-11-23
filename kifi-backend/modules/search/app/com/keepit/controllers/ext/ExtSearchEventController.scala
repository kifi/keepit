package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.controller.{SearchServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.heimdal.{SearchEngine, SearchAnalytics}
import com.keepit.search._
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.common.logging.Logging
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.model.ExperimentType
import com.keepit.model.NormalizedURI
import com.keepit.social.BasicUser
import play.api.libs.json.JsArray
import com.keepit.search.ClickedURI
import com.keepit.search.BrowsedURI
import com.keepit.search.ArticleSearchResult
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.net.{Host, URI}
import com.keepit.common._
import play.api.libs.concurrent.Execution.Implicits._

class ExtSearchEventController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  shoeboxClient: ShoeboxServiceClient,
  clickHistoryTracker: ClickHistoryTracker,
  browsingHistoryTracker: BrowsingHistoryTracker,
  resultClickedTracker: ResultClickTracker,
  articleSearchResultStore: ArticleSearchResultStore,
  searchAnalytics: SearchAnalytics)
  (implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices)
  extends BrowserExtensionController(actionAuthenticator) with SearchServiceController with Logging {


  def clickedSearchResult = AuthenticatedJsonToJsonAction { request =>
    val time = currentDateTime
    val userId = request.userId
    val json = request.body
    val query = (json \ "query").as[String]
    val uuid = ExternalId[ArticleSearchResult]((json \ "uuid").asOpt[String].getOrElse((json \ "queryUUID").as[String]))
    val searchExperiment = (json \ "experimentId").asOpt[Long].map(Id[SearchConfigExperiment](_))
    val origin = (json \ "origin").as[String]
    val kifiCollapsed = (json \ "kifiCollapsed").as[Boolean]
    val kifiTime = (json \ "kifiTime").as[Int]
    val referenceTime = (json \ "referenceTime").asOpt[Int]
    val resultSource = (json \ "resultSource").as[String]
    val resultPosition = (json \ "resultPosition").as[Int]
    val kifiResults = (json \ "kifiResults").as[Int]
    val isDemo = request.experiments.contains(ExperimentType.DEMO)

    SearchEngine.get(resultSource) match {

      case SearchEngine.Kifi => {
        val personalSearchResult = (json \ "hit").as[PersonalSearchResult]
        shoeboxClient.getNormalizedURIByURL(personalSearchResult.hit.url).onSuccess { case Some(uri) =>
          val uriId = uri.id.get
          clickHistoryTracker.add(userId, ClickedURI(uriId))
          resultClickedTracker.add(userId, query, uriId, resultPosition, personalSearchResult.isMyBookmark, isDemo)
          if (personalSearchResult.isMyBookmark) shoeboxClient.clickAttribution(userId, uriId) else shoeboxClient.clickAttribution(userId, uriId, personalSearchResult.users.map(_.externalId): _*)
        }
        searchAnalytics.clickedSearchResult(request, userId, time, origin, uuid, searchExperiment, query, kifiResults, kifiCollapsed, kifiTime, referenceTime, SearchEngine.Kifi, resultPosition, Some(personalSearchResult))
      }

      case theOtherGuys => {
        val searchResultUrl = (json \ "resultUrl").as[String]

        getDestinationUrl(searchResultUrl, theOtherGuys).foreach { url =>
          shoeboxClient.getNormalizedURIByURL(url).onSuccess {
            case Some(uri) =>
              val uriId = uri.id.get
              clickHistoryTracker.add(userId, ClickedURI(uri.id.get))
              resultClickedTracker.add(userId, query, uriId, resultPosition, false) // We do this for a Google result, too.
            case None =>
              resultClickedTracker.moderate(userId, query)
          }
        }
        searchAnalytics.clickedSearchResult(request, userId, time, origin, uuid, searchExperiment, query, kifiResults, kifiCollapsed, kifiTime, referenceTime, theOtherGuys, resultPosition, None)
      }
    }
    Ok
  }

  def endedSearch = AuthenticatedJsonToJsonAction { request =>
    val time = currentDateTime
    val userId = request.userId
    val json = request.body
    val uuid = ExternalId[ArticleSearchResult]((json \ "uuid").asOpt[String].getOrElse((json \ "queryUUID").as[String]))
    val searchExperiment = (json \ "experimentId").asOpt[Long].map(Id[SearchConfigExperiment](_))
    val kifiResults = (json \ "kifiResults").as[Int]
    val kifiCollapsed = (json \ "kifiCollapsed").as[Boolean]
    val kifiResultsClicked = (json \ "kifiResultsClicked").as[Int]
    val otherResultsClicked = (json \ "searchResultsClicked").as[Int]
    val kifiTime = (json \ "kifiTime").as[Int]
    val referenceTime = (json \ "referenceTime").asOpt[Int]
    val origin = (json \ "origin").as[String]
    searchAnalytics.endedSearch(request, userId, time, origin, uuid, searchExperiment, kifiResults, kifiCollapsed, kifiTime, referenceTime, otherResultsClicked, kifiResultsClicked)
    Ok
  }

  def updateBrowsingHistory() = AuthenticatedJsonToJsonAction { request =>
    val userId = request.userId
    val browsed = request.body.as[JsArray].value.map(Id.format[NormalizedURI].reads)
    browsed.foreach(uriIdJs => browsingHistoryTracker.add(userId, BrowsedURI(uriIdJs.get)))
    Ok
  }

  private def getDestinationUrl(searchResultUrl: String, searchEngine: SearchEngine): Option[String] = {
    searchEngine match {
      case SearchEngine.Google => searchResultUrl match {
        case URI(_, _, Some(Host("com", "youtube", _*)), _, _, _, _) => Some(searchResultUrl)
        case URI(_, _, Some(Host("org", "wikipedia", _*)), _, _, _, _) => Some(searchResultUrl)
        case URI(_, _, Some(host), _, Some("/url"), Some(query), _) if host.domain.contains("google") => query.params.find(_.name == "url").flatMap { _.decodedValue }
        case _ => None
      }
      case _ => None
    }
  } tap { urlOpt => if (urlOpt.isEmpty) log.error(s"failed to extract the destination URL from $searchEngine: $searchResultUrl") }
}
