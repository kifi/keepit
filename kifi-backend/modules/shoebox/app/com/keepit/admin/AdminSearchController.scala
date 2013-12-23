package com.keepit.controllers.admin

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.search._
import views.html
import scala.concurrent.Await
import scala.concurrent.duration._
import play.api.libs.json._
import scala.concurrent.Future
import com.keepit.common.logging.Logging
import scala.util.Random


case class ArticleSearchResultHitMeta(uri: NormalizedURI, users: Seq[User], scoring: Scoring, hit: ArticleHit)

case class MinimalHit(rank: Int, title: String, url: String)
case class ConfigIdAndHits(id: Long, hits: Seq[MinimalHit] )
case class BlindTestReturn(msg: String, result1: Option[ConfigIdAndHits], result2: Option[ConfigIdAndHits])

object BlindTestReturn{
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
    searchClient: SearchServiceClient
  ) extends AdminController(actionAuthenticator) with Logging {

  val rand = new Random()

  def explain(query: String, uriId: Id[NormalizedURI], lang: String) = AdminHtmlAction { request =>
    Async {
      searchClient.explainResult(query, request.userId, uriId, lang).map(Ok(_))
    }
  }

  private def getConfigsForBlindTest: Seq[SearchConfigExperiment] = {
    db.readOnly{ implicit s =>
      searchConfigRepo.getActive()
    }.filter(_.description.contains("[blind test]"))
  }

  private def fakeGetConfigsForBlindTest: Seq[SearchConfigExperiment] = {
    val config = Await.result(searchClient.getSearchDefaultConfig, 1 second)
    Seq(SearchConfigExperiment(id = Some(Id[SearchConfigExperiment](42)), config = config),
        SearchConfigExperiment(id = Some(Id[SearchConfigExperiment](24)), config = config))
  }

  def blindTestVoted() = AdminHtmlAction{ request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val id1 = body.get("configId1").get
    val id2 = body.get("configId2").get
    val vote = body.get("vote").get

    log.info(s"two configs: $id1, $id2, voting result: $vote")
    Ok
  }

  def blindTest() = AdminHtmlAction { request =>
    request.body.asFormUrlEncoded.get
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    println("\n===\n" + body)
    val userId = body.get("userId").get.toLong
    val query = body.get("query").get
    val maxHits = body.get("maxHits").get.toInt
    val id1 = body.get("id1").get.toLong
    val id2 = body.get("id2").get.toLong

    val (config1, config2) = db.readOnly{ implicit s =>
      (searchConfigRepo.get(Id[SearchConfigExperiment](id1)), searchConfigRepo.get(Id[SearchConfigExperiment](id2)))
    }

//    val configs = fakeGetConfigsForBlindTest
//    val (config1, config2) = (configs(0), configs(1))

    val hitsFuture = Future.sequence(Seq(searchClient.searchWithConfig(Id[User](userId), query, maxHits, config1.config),
                     searchClient.searchWithConfig(Id[User](userId), query, maxHits, config2.config)))
    val hits = Await.result(hitsFuture, 1 seconds)
    val (hits1, hits2) = (hits(0).zipWithIndex.map{ case ((uriId, title, url), idx) => MinimalHit(idx + 1, title, url) },
        hits(1).zipWithIndex.map{ case ((uriId, title, url), idx) => MinimalHit(idx + 1, title, url) })

    // random shuffle
    val rv = if (rand.nextInt() % 2 == 0) {
      BlindTestReturn("OK", Some(ConfigIdAndHits(id1, hits1)), Some(ConfigIdAndHits(id2, hits2)))
    } else BlindTestReturn("OK", Some(ConfigIdAndHits(id2, hits2)), Some(ConfigIdAndHits(id1, hits1)))

    Ok(Json.toJson(rv))
  }

  def blindTestPage() = AdminHtmlAction { request =>
    val configs = getConfigsForBlindTest
    Ok(html.admin.adminSearchBlindTest(configs))
  }

  def articleSearchResult(id: ExternalId[ArticleSearchResult]) = AdminHtmlAction { implicit request =>

    val result = articleSearchResultStore.get(id).get
    val metas: Seq[ArticleSearchResultHitMeta] = db.readOnly { implicit s =>
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
