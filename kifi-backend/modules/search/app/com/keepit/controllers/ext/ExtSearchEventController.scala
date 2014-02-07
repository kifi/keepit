package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.controller.{SearchServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.heimdal._
import com.keepit.search._
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.common.logging.Logging
import com.keepit.model.ExperimentType
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.net.{Host, URI}
import com.keepit.common._
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.common.healthcheck._
import play.api.libs.json._
import com.keepit.search.tracker.ClickedURI
import scala.Some
import com.keepit.search.tracker.BrowsedURI
import com.keepit.common.akka.SafeFuture
import com.keepit.search.tracker.BrowsingHistoryTracker
import com.keepit.search.tracker.ClickHistoryTracker
import com.keepit.search.tracker.ResultClickTracker

class ExtSearchEventController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  shoeboxClient: ShoeboxServiceClient,
  clickHistoryTracker: ClickHistoryTracker,
  browsingHistoryTracker: BrowsingHistoryTracker,
  resultClickedTracker: ResultClickTracker,
  articleSearchResultStore: ArticleSearchResultStore,
  searchAnalytics: SearchAnalytics,
  healthCheckMailer: SystemAdminMailSender,
  heimdalContextBuilder: HeimdalContextBuilderFactory)
  (implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices)
  extends BrowserExtensionController(actionAuthenticator) with SearchServiceController with Logging {


  def clickedSearchResult = JsonAction.authenticatedParseJsonAsync { request =>
    val time = currentDateTime
    val userId = request.userId
    val json = request.body
    val basicSearchContext = json.as[BasicSearchContext]
    val resultSource = (json \ "resultSource").as[String]
    val resultPosition = (json \ "resultPosition").as[Int]
    val searchResultUrl = (json \ "resultUrl").as[String]
    val isDemo = request.experiments.contains(ExperimentType.DEMO)
    val query = basicSearchContext.query

    SafeFuture {
      val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
      SearchEngine.get(resultSource) match {

        case SearchEngine.Kifi => {
          val kifiHitContext = (json \ "hit").as[KifiHitContext]
          shoeboxClient.getNormalizedURIByURL(searchResultUrl).onSuccess { case Some(uri) =>
            val uriId = uri.id.get
            clickHistoryTracker.add(userId, ClickedURI(uriId))
            resultClickedTracker.add(userId, query, uriId, resultPosition, kifiHitContext.isOwnKeep, isDemo)
            if (kifiHitContext.isOwnKeep) shoeboxClient.clickAttribution(userId, uriId) else shoeboxClient.clickAttribution(userId, uriId, kifiHitContext.keepers: _*)
          }
          searchAnalytics.clickedSearchResult(userId, time, basicSearchContext, SearchEngine.Kifi, resultPosition, Some(kifiHitContext), contextBuilder)
        }

        case theOtherGuys => {

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
          searchAnalytics.clickedSearchResult(userId, time, basicSearchContext, theOtherGuys, resultPosition, None, contextBuilder)
        }
      }
      Ok
    }
  }

  def endedSearch = JsonAction.authenticatedParseJson { request =>
    // Deprecated
    Ok
  }

  def searched = JsonAction.authenticatedParseJsonAsync { request =>
    val time = currentDateTime
    val userId = request.userId
    val json = request.body
    val basicSearchContext = json.as[BasicSearchContext]
    val endedWith = (json \ "endedWith").as[String]
    SafeFuture {
      val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
      searchAnalytics.searched(userId, time, basicSearchContext, endedWith, contextBuilder)
      Ok
    }
  }

  def updateBrowsingHistory() = JsonAction.authenticatedParseJson { request =>
    val userId = request.userId
    val browsedUrls = request.body.as[JsArray].value.map(_.as[String])
    browsedUrls.foreach { url =>
      shoeboxClient.getNormalizedURIByURL(url).foreach(_.foreach { uri =>
        browsingHistoryTracker.add(userId, BrowsedURI(uri.id.get))
      })
    }
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

