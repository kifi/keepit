package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.controller.{AuthenticatedRequest, SearchServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.heimdal.{HeimdalContextBuilderFactory, BasicSearchContext, SearchEngine, SearchAnalytics}
import com.keepit.search._
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.common.logging.Logging
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.model.ExperimentType
import com.keepit.model.{User, NormalizedURI}
import com.keepit.search.ClickedURI
import com.keepit.search.BrowsedURI
import com.keepit.search.ArticleSearchResult
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.net.{Host, URI}
import com.keepit.common._
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.common.healthcheck._
import com.typesafe.plugin.MailerPlugin
import com.keepit.common.healthcheck.Healthcheck.EMAIL
import com.keepit.common.mail.{PostOffice, EmailAddresses, ElectronicMail}
import play.api.libs.json._
import com.keepit.common.controller.AuthenticatedRequest
import com.keepit.search.ClickedURI
import scala.Some
import com.keepit.search.BrowsedURI
import com.keepit.search.ArticleSearchResult
import com.keepit.common.akka.SafeFuture

class ExtSearchEventController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  shoeboxClient: ShoeboxServiceClient,
  clickHistoryTracker: ClickHistoryTracker,
  browsingHistoryTracker: BrowsingHistoryTracker,
  resultClickedTracker: ResultClickTracker,
  articleSearchResultStore: ArticleSearchResultStore,
  searchAnalytics: SearchAnalytics,
  healthCheckMailer: HealthcheckMailSender,
  heimdalContextBuilder: HeimdalContextBuilderFactory)
  (implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices)
  extends BrowserExtensionController(actionAuthenticator) with SearchServiceController with Logging {


  def clickedSearchResult = AuthenticatedJsonToJsonAction { request =>
    val time = currentDateTime
    val userId = request.userId
    val json = request.body
    val basicSearchContext = json.as[BasicSearchContext]
    val resultSource = (json \ "resultSource").as[String]
    val resultPosition = (json \ "resultPosition").as[Int]
    val isDemo = request.experiments.contains(ExperimentType.DEMO)
    val query = basicSearchContext.query

    Async(SafeFuture {
      val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
      SearchEngine.get(resultSource) match {

        case SearchEngine.Kifi => {
          val hit = KifiSearchHit((json \ "hit").as[JsObject])
          shoeboxClient.getNormalizedURIByURL(hit.bookmark.url).onSuccess { case Some(uri) =>
            val uriId = uri.id.get
            clickHistoryTracker.add(userId, ClickedURI(uriId))
            resultClickedTracker.add(userId, query, uriId, resultPosition, hit.isMyBookmark, isDemo)
            if (hit.isMyBookmark) shoeboxClient.clickAttribution(userId, uriId) else shoeboxClient.clickAttribution(userId, uriId, hit.users.map(_.externalId): _*)
          }
          searchAnalytics.clickedSearchResult(userId, time, basicSearchContext, SearchEngine.Kifi, resultPosition, Some(hit), contextBuilder)
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
          searchAnalytics.clickedSearchResult(userId, time, basicSearchContext, theOtherGuys, resultPosition, None, contextBuilder)
        }
      }
      Ok
    })
  }

  def endedSearch = AuthenticatedJsonToJsonAction { request =>
    // Deprecated
    Ok
  }

  def searched = AuthenticatedJsonToJsonAction { request =>
    val time = currentDateTime
    val userId = request.userId
    val json = request.body
    val basicSearchContext = json.as[BasicSearchContext]
    val endedWith = (json \ "endedWith").as[String]
    Async(SafeFuture {
      val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
      searchAnalytics.searched(userId, time, basicSearchContext, endedWith, contextBuilder)
      Ok
    })
  }

  def updateBrowsingHistory() = AuthenticatedJsonToJsonAction { request =>
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

