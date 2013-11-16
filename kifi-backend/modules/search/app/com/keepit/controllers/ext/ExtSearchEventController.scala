package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.controller.{SearchServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.heimdal.{SearchEngine, SearchAnalytics}
import com.keepit.search._
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.common.logging.Logging
import com.keepit.common.db.{ExternalId, Id}
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


  def resultClicked = AuthenticatedJsonToJsonAction { request =>
    val time = currentDateTime
    val userId = request.userId
    val json = request.body
    val query = (json \ "query").as[String]
    val queryUUID = ExternalId[ArticleSearchResult]((json \ "searchUUID").as[String])
    val searchExperiment = (json \ "experimentId").asOpt[Long].map(Id[SearchConfigExperiment](_))
    val origin = (json \ "origin").as[String]
    val kifiCollapsed = (json \ "kifiCollapsed").as[Boolean]
    val initialKifiSearchDeliveryTime = (json \ "kifiTime").as[Int]
    val initialReferenceDeliveryTime = (json \ "referenceTime").as[Int]
    val resultSource = (json \ "resultSource").as[String]
    val resultPosition = (json \ "resultPosition").as[Int]
    val kifiResults = (json \ "kifiResults").as[Int]

    SearchEngine.get(resultSource) match {
      case SearchEngine.Kifi => {
        val hit = articleSearchResultStore.get(queryUUID).map { articleSearchResult =>
          val hitIndex = resultPosition - articleSearchResult.previousHits
          val hit = articleSearchResult.hits(hitIndex)
          val uriId = hit.uriId
          clickHistoryTracker.add(userId, ClickedURI(uriId))
          resultClickedTracker.add(userId, query, uriId, resultPosition, hit.isMyBookmark)
          if (hit.isMyBookmark) shoeboxClient.clickAttribution(userId, uriId) else shoeboxClient.clickAttribution(userId, uriId, hit.users: _*)
          hit
        }

        val bookmarkCount = hit.map(_.bookmarkCount)
        val isUserKeep = hit.map(_.isMyBookmark)
        val isPrivate = hit.map(_.isPrivate)
        val keepers = hit.map(_.users.length)
        searchAnalytics.kifiResultClicked(userId, Some(queryUUID), searchExperiment, resultPosition, bookmarkCount, keepers, isUserKeep.get, isPrivate, kifiResults, Some(kifiCollapsed), time)
      }

      case theOtherGuys => {
        val searchResultUrl = (json \ "searchResultUrl").as[String]

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
        searchAnalytics.searchResultClicked(userId, Some(queryUUID), searchExperiment, theOtherGuys, resultPosition, kifiResults, Some(kifiCollapsed), time)
      }
    }
    Ok
  }

  def searchEnded = AuthenticatedJsonToJsonAction { request =>
    val time = currentDateTime
    val userId = request.userId
    val json = request.body
    val queryUUID = ExternalId.asOpt[ArticleSearchResult]((json \ "searchUUID").asOpt[String].getOrElse(""))
    val searchExperiment = (json \ "experimentId").asOpt[Long].map(Id[SearchConfigExperiment](_))
    val kifiResults = (json \ "kifiResults").as[Int]
    val kifiCollapsed = (json \ "kifiCollapsed").as[Boolean]
    val kifiResultsClicked = (json \ "kifiResultsClicked").as[Int]
    val searchResultsClicked = (json \ "searchResultsClicked").as[Int]
    val initialKifiSearchDeliveryTime = (json \ "kifiTime").as[Int]
    val initialReferenceDeliveryTime = (json \ "referenceTime").as[Int]
    val origin = SearchEngine.get((json \ "origin").as[String])
    searchAnalytics.searchEnded(userId, queryUUID, searchExperiment, kifiResults, kifiResultsClicked, origin, searchResultsClicked, Some(kifiCollapsed), time)
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
