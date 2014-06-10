package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.graph.GraphServiceClient
import com.keepit.model._
import com.keepit.graph.wander.{Wanderlust}
import com.keepit.common.db.slick.Database
import com.keepit.common.db.slick.Database.Slave
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.time._
import play.api.mvc.{SimpleResult}
import scala.concurrent.Promise
import scala.util.{Failure, Success}
import scala.concurrent.duration.Duration

class WanderingAdminController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  graphClient: GraphServiceClient,
  db: Database,
  userRepo: UserRepo,
  socialUserRepo: SocialUserInfoRepo,
  uriRepo: NormalizedURIRepo,
  clock: Clock
) extends AdminController(actionAuthenticator) {

  def wander() = AdminHtmlAction.authenticatedAsync { implicit request =>
    request.request.method.toLowerCase match {
      case "get" => {
        graphClient.getGraphKinds().map { graphKinds =>
          val availableVertexKinds = (graphKinds.vertexKinds -- Set("Thread", "Tag")).toSeq.sorted
          val userVertexType = availableVertexKinds.find(_.toLowerCase.startsWith("user")).get
          val wanderlust = Wanderlust(userVertexType, request.userId.id)
          Ok(views.html.admin.graph.wanderView(availableVertexKinds, wanderlust, None, Seq.empty, Seq.empty, Seq.empty, Seq.empty))
        }
      }

      case "post" => {
        val start = clock.now()
        val body = request.body.asFormUrlEncoded.get
        val availableVertexKinds = body("availableVertexKinds")
        val startingVertexKind = body("kind").head
        val startingVertexDataId = body("id").head.toLong
        val preferredCollisions = availableVertexKinds.toSet intersect body("preferredCollisions").toSet
        val avoidTrivialCollisions = body.contains("avoidTrivialCollisions")
        val steps = body("steps").head.toInt
        val restartProbability = body("restartProbability").head.toDouble
        val recency = body("recency").headOption.collect { case days if days.nonEmpty => Duration(days.toLong, "days") }
        val halfLife = body("decay").headOption.collect { case days if days.nonEmpty => Duration(days.toLong, "days") }

        val wanderlust = Wanderlust(startingVertexKind, startingVertexDataId, preferredCollisions, avoidTrivialCollisions, steps, restartProbability, recency, halfLife)

        val promisedResult = Promise[SimpleResult]()

        graphClient.wander(wanderlust).onComplete {

          case Failure(ex) => {
            val view = Ok(views.html.admin.graph.wanderView(availableVertexKinds, wanderlust, Some(Failure(ex)), Seq.empty, Seq.empty, Seq.empty, Seq.empty))
            promisedResult.success(view)
          }

          case Success(collisions) => {
            val sortedUsers = db.readOnly(dbMasterSlave = Slave) { implicit session =>
              collisions.users.map { case (userId, count) => userRepo.get(userId) -> count }
            }.toSeq.sortBy(- _._2)

            val sortedSocialUsers = db.readOnly(dbMasterSlave = Slave) { implicit session =>
              collisions.socialUsers.map { case (socialUserInfoId, count) => socialUserRepo.get(socialUserInfoId) -> count }
            }.toSeq.sortBy(- _._2)

            val sortedUris = db.readOnly(dbMasterSlave = Slave) { implicit session =>
              collisions.uris.map { case (uriId, count) => uriRepo.get(uriId) -> count }
            }.toSeq.sortBy(- _._2)

            val sortedExtras = collisions.extra.toSeq.sortBy(- _._2)

            val end = clock.now()
            val timing = end.getMillis - start.getMillis

            val view = Ok(views.html.admin.graph.wanderView(availableVertexKinds, wanderlust, Some(Success(timing)), sortedUsers, sortedSocialUsers, sortedUris, sortedExtras))
            promisedResult.success(view)
          }
        }

        promisedResult.future
      }
    }
  }
}
