package com.keepit.controllers.mobile

import com.keepit.social.BasicUser
import com.keepit.model._
import com.keepit.common.controller.{ActionAuthenticator, ShoeboxServiceController, MobileController}
import com.google.inject.Inject
import com.keepit.search.SearchServiceClient
import com.keepit.graph.GraphServiceClient
import com.keepit.graph.wander.Wanderlust
import scala.concurrent.Future
import com.keepit.commanders.URISummaryCommander
import com.keepit.common.db.slick.Database
import com.keepit.common.domain.DomainToNameMapper
import com.keepit.common.social.BasicUserRepo
import scala.Some
import play.api.libs.json.{JsArray, Json}
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class MobileDiscoveryController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  searchClient: SearchServiceClient,
  graphClient: GraphServiceClient,
  db: Database,
  uriRepo: NormalizedURIRepo,
  basicUserRepo: BasicUserRepo,
  uriSummaryCommander: URISummaryCommander
) extends MobileController(actionAuthenticator) with ShoeboxServiceController {

  def discover(withPageInfo: Boolean, limit: Int = -1) = JsonAction.authenticatedParseJsonAsync { request =>
    val userId = request.userId
    val futureUriCollisionInfos = graphClient.wander(Wanderlust.discovery(userId)).flatMap { collisions =>
      val sortedUriCollisions = collisions.uris.toSeq.sortBy(- _._2)
      val relevantUris = if (limit > 0) sortedUriCollisions.take(limit) else sortedUriCollisions
      val (uriIds, scores) = relevantUris.unzip
      val sharingInfosFuture = searchClient.sharingUserInfo(userId, uriIds)
      val uris = db.readOnly { implicit session => uriIds.map(uriRepo.get) }
      val pageInfosFuture = Future.sequence(uris.map { uri =>
        if (withPageInfo) {
          val request = URISummaryRequest(
            url = uri.url,
            imageType = ImageType.ANY,
            withDescription = true,
            waiting = false,
            silent = false
          )
          uriSummaryCommander.getURISummaryForRequest(request).map(Some(_))
        } else {
          Future.successful(None)
        }
      })

      for {
        sharingInfos <- sharingInfosFuture
        pageInfos <- pageInfosFuture
      } yield {

        val idToBasicUser = db.readOnly { implicit s =>
          basicUserRepo.loadAll(sharingInfos.flatMap(_.sharingUserIds).toSet)
        }

        val uriCollisionInfos = (uris zip sharingInfos zip pageInfos zip scores).map { case (((uri, sharingInfo), pageInfo), score) =>
          val others = sharingInfo.keepersEdgeSetSize - sharingInfo.sharingUserIds.size
          UriCollisionInfo(
            uri,
            sharingInfo.sharingUserIds map idToBasicUser,
            others,
            DomainToNameMapper.getNameFromUrl(uri.url),
            pageInfo,
            score
          )
        }
        uriCollisionInfos
      }
    }
    futureUriCollisionInfos.map { uriCollisionInfos =>
      Ok(JsArray(uriCollisionInfos.map(UriCollisionInfo.format.writes(_))))
    }
  }
}

case class UriCollisionInfo(uri: NormalizedURI, users: Set[BasicUser], others: Int, siteName: Option[String] = None, uriSummary: Option[URISummary] = None, score: Int)

object UriCollisionInfo {
  implicit val format = Json.format[UriCollisionInfo]
}
