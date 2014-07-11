package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ AdminController, ActionAuthenticator }
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.search._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.Random
import views.html

case class ArticleSearchResultHitMeta(uri: NormalizedURI, users: Seq[User], scoring: Scoring, hit: ArticleHit)

case class MinimalHit(rank: Int, title: String, url: String)
case class ConfigIdAndHits(id: Long, hits: Seq[MinimalHit])
case class BlindTestReturn(msg: String, result1: Option[ConfigIdAndHits], result2: Option[ConfigIdAndHits])

object BlindTestReturn {
  implicit def minimalHitFormat = Json.format[MinimalHit]
  implicit def configIdAndHitFormat = Json.format[ConfigIdAndHits]
  implicit def format = Json.format[BlindTestReturn]
}

class AdminSearchController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    db: Database,
    userRepo: UserRepo,
    articleSearchResultStore: ArticleSearchResultStore,
    uriRepo: NormalizedURIRepo,
    searchConfigRepo: SearchConfigExperimentRepo,
    searchClient: SearchServiceClient,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    heimdal: HeimdalServiceClient) extends AdminController(actionAuthenticator) with Logging {

  val rand = new Random()

  def explain(query: String, uriId: Id[NormalizedURI], lang: String) = AdminHtmlAction.authenticatedAsync { request =>
    searchClient.explainResult(query, request.userId, uriId, lang).map(Ok(_))
  }

  private def getConfigsForBlindTest: Seq[SearchConfigExperiment] = {
    db.readOnlyReplica { implicit s =>
      searchConfigRepo.getActive()
    }.filter(_.description.contains("[blind test]"))
  }

  private def fakeGetConfigsForBlindTest: Seq[SearchConfigExperiment] = {
    val config = Await.result(searchClient.getSearchDefaultConfig, 1 second)
    Seq(SearchConfigExperiment(id = Some(Id[SearchConfigExperiment](42)), config = config),
      SearchConfigExperiment(id = Some(Id[SearchConfigExperiment](24)), config = config))
  }

  def blindTestVoted() = AdminHtmlAction.authenticated { request =>
    log.info("search blind test: results voted")
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val id1 = body.get("configId1").get.toLong
    val id2 = body.get("configId2").get.toLong
    val vote = body.get("vote").get

    log.info(s"two configs: $id1, $id2, voting result: $vote")

    val builder = new HeimdalContextBuilder()
    builder += ("subject", "Search Experiment Blind Test")
    builder += ("options", Seq(id1, id2))
    builder += ("vote", vote)

    heimdal.trackEvent(UserEvent(request.userId, builder.build, UserEventTypes.VOTED))
    Ok
  }

  def blindTest() = AdminHtmlAction.authenticated { request =>
    log.info("search blind test: fetching results")
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val userId = body.get("userId").get.toLong
    val query = body.get("query").get
    val maxHits = body.get("maxHits").get.toInt
    val id1 = body.get("id1").get.toLong
    val id2 = body.get("id2").get.toLong
    val shuffle = body.get("shuffle").get.toBoolean

    val (config1, config2) = db.readOnlyReplica { implicit s =>
      (searchConfigRepo.get(Id[SearchConfigExperiment](id1)), searchConfigRepo.get(Id[SearchConfigExperiment](id2)))
    }

    //    val configs = fakeGetConfigsForBlindTest
    //    val (config1, config2) = (configs(0), configs(1))

    val hitsFuture = Future.sequence(Seq(searchClient.searchWithConfig(Id[User](userId), query, maxHits, config1.config),
      searchClient.searchWithConfig(Id[User](userId), query, maxHits, config2.config)))
    val hits = Await.result(hitsFuture, 1 seconds)
    val (hits1, hits2) = (hits(0).zipWithIndex.map { case ((uriId, title, url), idx) => MinimalHit(idx + 1, title, url) },
      hits(1).zipWithIndex.map { case ((uriId, title, url), idx) => MinimalHit(idx + 1, title, url) })

    val rv = if (shuffle && rand.nextInt() % 2 == 1) {
      BlindTestReturn("OK", Some(ConfigIdAndHits(id2, hits2)), Some(ConfigIdAndHits(id1, hits1)))
    } else {
      BlindTestReturn("OK", Some(ConfigIdAndHits(id1, hits1)), Some(ConfigIdAndHits(id2, hits2)))
    }

    Ok(Json.toJson(rv))
  }

  def blindTestPage() = AdminHtmlAction.authenticated { request =>
    val configs = getConfigsForBlindTest
    Ok(html.admin.adminSearchBlindTest(configs))
  }

  def searchComparisonPage() = AdminHtmlAction.authenticated { request =>
    val configs = getConfigsForBlindTest
    Ok(html.admin.adminSearchComparison(configs))
  }

  def articleSearchResult(id: ExternalId[ArticleSearchResult]) = AdminHtmlAction.authenticated { implicit request =>

    val result = articleSearchResultStore.get(id).get
    val metas: Seq[ArticleSearchResultHitMeta] = db.readOnlyReplica { implicit s =>
      result.hits.zip(result.scorings) map { tuple =>
        val hit = tuple._1
        val scoring = tuple._2
        val uri = uriRepo.get(hit.uriId)
        val users = hit.users.map { userId =>
          userRepo.get(userId)
        }
        ArticleSearchResultHitMeta(uri, users.toSeq, scoring, hit)
      }
    }
    Ok(html.admin.articleSearchResult(result, metas))
  }
}
