package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.controller.{AuthenticatedRequest, SearchServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.heimdal.{SearchEngine, SearchAnalytics}
import com.keepit.search._
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.common.logging.Logging
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.model.ExperimentType
import com.keepit.model.{User, NormalizedURI}
import play.api.libs.json.{JsValue, JsArray}
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
import play.api.libs.json.JsArray
import com.keepit.common.controller.AuthenticatedRequest
import com.keepit.search.ClickedURI
import scala.Some
import com.keepit.search.BrowsedURI
import com.keepit.search.ArticleSearchResult

class ExtSearchEventController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  shoeboxClient: ShoeboxServiceClient,
  clickHistoryTracker: ClickHistoryTracker,
  browsingHistoryTracker: BrowsingHistoryTracker,
  resultClickedTracker: ResultClickTracker,
  articleSearchResultStore: ArticleSearchResultStore,
  searchAnalytics: SearchAnalytics,
  healthCheckMailer: HealthcheckMailSender)
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
    val kifiTime = (json \ "kifiTime").asOpt[Int]
    val kifiShownTime = (json \ "kifiShownTime").asOpt[Int]
    val thirdPartyShownTime = (json \ "thirdPartyShownTime").asOpt[Int] orElse (json \ "referenceTime").asOpt[Int]
    val resultSource = (json \ "resultSource").as[String]
    val resultPosition = (json \ "resultPosition").as[Int]
    val kifiResults = (json \ "kifiResults").as[Int]
    val queryRefinements = (json \ "refinements").asOpt[Int]
    val isDemo = request.experiments.contains(ExperimentType.DEMO)

    checkForMissingDeliveryTimes(kifiTime, kifiShownTime, thirdPartyShownTime, request, "ExtSearchEventController.clickedSearchResult")
    SearchEngine.get(resultSource) match {

      case SearchEngine.Kifi => {
        val personalSearchResult = (json \ "hit").as[PersonalSearchResult]
        shoeboxClient.getNormalizedURIByURL(personalSearchResult.hit.url).onSuccess { case Some(uri) =>
          val uriId = uri.id.get
          clickHistoryTracker.add(userId, ClickedURI(uriId))
          resultClickedTracker.add(userId, query, uriId, resultPosition, personalSearchResult.isMyBookmark, isDemo)
          if (personalSearchResult.isMyBookmark) shoeboxClient.clickAttribution(userId, uriId) else shoeboxClient.clickAttribution(userId, uriId, personalSearchResult.users.map(_.externalId): _*)
        }
        searchAnalytics.clickedSearchResult(request, userId, time, origin, uuid, searchExperiment, query, queryRefinements, kifiResults, kifiCollapsed, kifiTime, kifiShownTime, thirdPartyShownTime, SearchEngine.Kifi, resultPosition, Some(personalSearchResult))
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
        searchAnalytics.clickedSearchResult(request, userId, time, origin, uuid, searchExperiment, query, queryRefinements, kifiResults, kifiCollapsed, kifiTime, kifiShownTime, thirdPartyShownTime, theOtherGuys, resultPosition, None)
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
    val kifiTime = (json \ "kifiTime").asOpt[Int]
    val kifiShownTime = (json \ "kifiShownTime").asOpt[Int]
    val thirdPartyShownTime = (json \ "thirdPartyShownTime").asOpt[Int] orElse (json \ "referenceTime").asOpt[Int]
    val origin = (json \ "origin").as[String]
    val queryRefinements = (json \ "refinements").asOpt[Int]
    checkForMissingDeliveryTimes(kifiTime, kifiShownTime, thirdPartyShownTime, request, "ExtSearchEventController.endedSearch")
    searchAnalytics.endedSearch(request, userId, time, origin, uuid, searchExperiment, queryRefinements, kifiResults, kifiCollapsed, kifiTime, kifiShownTime, thirdPartyShownTime, otherResultsClicked, kifiResultsClicked)
    Ok
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

  private def checkForMissingDeliveryTimes(kifiTime: Option[Int], kifiShownTime: Option[Int], thirdPartyShownTime: Option[Int], request: AuthenticatedRequest[JsValue], method: String) = {
    kifiTime match {
      case None => reportToLéo(AirbrakeError.incoming(request, message = s"[$method: User ${request.userId}] Kifi delivery time is missing."))
      case Some(time) => if (time < 0) reportToLéo(AirbrakeError.incoming(request, message = s"[$method: User ${request.userId}] Kifi delivery time is negative."))
    }

    thirdPartyShownTime match {
      case None => reportToLéo(AirbrakeError.incoming(request, message = s"[$method: User ${request.userId}] Google shown time is missing."))
      case Some(time) => if (time < 0) reportToLéo(AirbrakeError.incoming(request, message = s"[$method: User ${request.userId}] Google shown time is negative."))
    }

    kifiShownTime match {
      case None => reportToLéo(AirbrakeError.incoming(request, message = s"[$method: User ${request.userId}] Kifi shown time is missing."))
      case Some(time) => if (time < 0) reportToLéo(AirbrakeError.incoming(request, message = s"[$method: User ${request.userId}] Kifi shown time is negative."))
    }
  }

  private def reportToLéo(error: AirbrakeError) = {
    val body = views.html.email.healthcheckMail(AirbrakeErrorHistory(error.signature, 1, 0, error), fortyTwoServices.started.toString, fortyTwoServices.currentService.name).body
    healthCheckMailer.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = Seq(EmailAddresses.LÉO),
      subject = "Missing Delivery Time in Search Statistics", htmlBody = body, category = PostOffice.Categories.System.HEALTHCHECK))
  }
}

