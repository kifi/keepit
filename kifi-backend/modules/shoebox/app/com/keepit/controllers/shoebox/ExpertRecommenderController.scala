package com.keepit.controllers.shoebox

import com.google.inject.{Provider, Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.Database
import com.keepit.common.zookeeper.CentralConfig
import com.keepit.model.NormalizedURI
import com.keepit.model.UserBookmarkClicksRepo
import com.keepit.model.BookmarkRepo
import com.keepit.model.UserStates
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.libs.json._
import com.keepit.model.{UriTopicRepoA, UriTopicRepoB}
import com.keepit.model.UserRepo
import com.keepit.common.db.Id
import com.keepit.model.User
import scala.collection.mutable.{Map => MutMap}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent._
import scala.util.Success
import com.keepit.common.healthcheck._
import ExecutionContext.Implicits.global
import com.keepit.common.logging.Logging
import com.keepit.learning.topicmodel._
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.akka.SlowRunningExecutionContext

@ImplementedBy(classOf[ExpertRecommenderControllerImpl])
trait ExpertRecommenderController extends ShoeboxServiceController{
  def init(): Unit
  def suggestExperts(): Action[AnyContent]
}

@Singleton
class ExpertRecommenderControllerImpl @Inject()(
  db: Database,
  userRepo: UserRepo,
  uriTopicRepoA: UriTopicRepoA,
  uriTopicRepoB: UriTopicRepoB,
  clicksRepo: UserBookmarkClicksRepo,
  bookmarkRepo: BookmarkRepo,
  centralConfig: CentralConfig,
  healthcheckPlugin: HealthcheckPlugin
) extends ExpertRecommenderController with Logging{
  var enabled = false
  var scoreMap = MutMap.empty[(Id[User], Int), Float]
  var modelFlag: Option[String] = None

  def init() = {
    val flagKey = new TopicModelFlagKey()
    val flag = centralConfig(flagKey)
    val rcmder = createExpertRecommender(flag)
    modelFlag = flag
    initScoreMap(rcmder)

    centralConfig.onChange(flagKey){ flagOpt =>
      val newFlag = centralConfig(flagKey)
      modelFlag = newFlag
      val newRcmder = createExpertRecommender(flag)
      initScoreMap(newRcmder)
    }
  }

  private def enableService() = { enabled = true }
  private def disableService() = { enabled = false }

  private def createExpertRecommender(flagOpt: Option[String]): ExpertRecommender = {
    flagOpt match {
      case Some(TopicModelAccessorFlag.A) => new ExpertRecommender(db, uriTopicRepoA, clicksRepo, bookmarkRepo)
      case Some(TopicModelAccessorFlag.B) => new ExpertRecommender(db, uriTopicRepoB, clicksRepo, bookmarkRepo)
      case _ => {
        healthcheckPlugin.addError(HealthcheckError(callType = Healthcheck.SEARCH,
          errorMessage = Some("flag from zookeeper does not make sense. Expert recommender has been default to use uriTopicRepoA")))
        new ExpertRecommender(db, uriTopicRepoA, clicksRepo, bookmarkRepo)
      }
    }
  }

  private def initScoreMap(rcmder: ExpertRecommender) = {
    disableService()

    genScoreMap(rcmder).onComplete{
      case Success(m) => scoreMap = m ; enableService()
      case _ =>  healthcheckPlugin.addError(HealthcheckError(callType = Healthcheck.SEARCH,
          errorMessage = Some("Error updating topics")))
    }
  }

  private def genScoreMap(rcmder: ExpertRecommender): Future[MutMap[(Id[User], Int), Float]] = {
    future{
      log.info(s"start generating user topic score maps, current model flag is ${modelFlag}")
      val t = System.currentTimeMillis()
      val userIds = db.readOnly{ implicit s =>
        userRepo.allExcluding(UserStates.PENDING, UserStates.BLOCKED).flatMap{_.id}
      }

      val scores = MutMap.empty[(Id[User], Int), Float]
      for(user <- userIds){
        rcmder.score(user).foreach{ x =>
          scores += (user, x._1) -> x._2
        }
      }
      log.info(s"topic score map has been generated for ${userIds.size} users. time elapsed: ${(System.currentTimeMillis() - t)/1000.0} seconds")
      scores
    } (SlowRunningExecutionContext.ec)
  }

  def rank(urisAndKeepers: Seq[(Id[NormalizedURI], Seq[Id[User]])]): Seq[(Id[User], Double)] = {
    if (enabled){
       val rcmder = createExpertRecommender(modelFlag)
       val users = urisAndKeepers.map{_._2}.flatten.toSet
       val hits = urisAndKeepers.map(_._1)
       val userhits = rcmder.userHits(urisAndKeepers)
       val topicPosterior = rcmder.estimateTopicPosterior(hits)
       val userScores = {
         users.map{ user =>
           var s = 0.0
           for((t, p) <- topicPosterior){
             s += p * scoreMap.getOrElse((user, t), 0.0f)
           }
           val boost = userhits.getOrElse(user, 0) * 1.0 / hits.length     // if user's hit num is low, probably she is not quite relevant to the query
           (user, s * boost)
         }.toArray
       }
       log.info(userScores.map{x => x._1.id + ": " + x._2}.mkString(";"))
       userScores.sortBy(-_._2)
    } else Nil
  }

  override def suggestExperts() = Action { request =>
    println("\n\n\nranking experts")
    val req = request.body.asJson.get.asInstanceOf[JsArray].value
    val urisAndKeepers = req.map{ js =>
      val uriId = Id[NormalizedURI]((js \ "uri").as[Long])
      val userIds = (js \ "users").as[JsArray].value.map{ x => Id[User](x.as[Long]) }
      (uriId, userIds)
    }
    val TOP_N = 4
    val ranks = rank(urisAndKeepers)
    val experts = ranks.take(TOP_N).filter(_._2 > 0.0).map{_._1}
    Ok(JsArray(experts.map{x => JsNumber(x.id)}))
  }
}