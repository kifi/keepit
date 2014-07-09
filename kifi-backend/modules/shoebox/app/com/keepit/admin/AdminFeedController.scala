package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.AdminController
import com.keepit.search.SearchServiceClient
import com.keepit.common.db.Id
import com.keepit.model.User
import scala.concurrent.Await
import scala.concurrent.duration._
import views.html
import com.keepit.search.feed.Feed
import com.keepit.cortex.CortexServiceClient
import com.keepit.model.KeepRepo
import com.keepit.common.db.slick.Database
import scala.util.Random


class AdminFeedController @Inject()(
  actionAuthenticator: ActionAuthenticator,
  searchClient: SearchServiceClient,
  cortex: CortexServiceClient,
  keepRepo: KeepRepo,
  db: Database
) extends AdminController(actionAuthenticator) {

  def index() = AdminHtmlAction.authenticated{ implicit request =>
    Ok(html.admin.feedQuery())
  }

  def getFeeds(userId: Id[User], limit: Int, smart: Boolean = false) = AdminHtmlAction.authenticated{ implicit request =>
    val start = System.currentTimeMillis()
    val feeds = Await.result(searchClient.getFeeds(userId, limit), 60 seconds)
    val elapsedSeconds = (System.currentTimeMillis() - start)/1000f

    if (!smart) {
      Ok(html.admin.feeds(userId, limit, feeds, elapsedSeconds))
    } else {
      val uriToFeed = feeds.map{ f => (f.uri.id.get, f)}.toMap
      val userKeeps = db.readOnlyMaster{ implicit s =>
        keepRepo.getByUser(userId)
      }

      val sampleSize = 100
      val userUriSamples = if (userKeeps.size <= sampleSize) userKeeps.map{_.uriId} else {
        Random.shuffle((0 until userKeeps.size).toList).take(sampleSize).map{ i => userKeeps(i).uriId}
      }

      val filtered = Await.result(cortex.word2vecFeedUserUris(userUriSamples, feeds.map{_.uri.id.get}), 60 seconds)
      val smartFeeds = filtered.map{ x => uriToFeed(x)}
      val elapse2 = (System.currentTimeMillis() - start)/1000f
      Ok(html.admin.feeds(userId, filtered.size, smartFeeds, elapse2))
    }
  }

  def queryFeeds() = AdminHtmlAction.authenticated { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val userId = Id[User](body.get("user").get.toLong)
    val limit = body.get("limit").get.toInt
    val smart = body.get("smart").isDefined
    Redirect(com.keepit.controllers.admin.routes.AdminFeedController.getFeeds(userId, limit, smart))
  }
}
