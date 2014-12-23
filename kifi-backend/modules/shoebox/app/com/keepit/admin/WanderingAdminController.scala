package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.db.Id
import com.keepit.graph.GraphServiceClient
import com.keepit.model._
import com.keepit.graph.wander.{ Wanderlust }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.slick.Database.Replica
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.time._
import play.api.mvc.{ Result }
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }
import scala.concurrent.duration._
import com.keepit.model.LibraryInfo
import com.keepit.social.BasicUser

class WanderingAdminController @Inject() (
    val userActionsHelper: UserActionsHelper,
    graphClient: GraphServiceClient,
    db: Database,
    userRepo: UserRepo,
    socialUserRepo: SocialUserInfoRepo,
    libraryRepo: LibraryRepo,
    uriRepo: NormalizedURIRepo,
    clock: Clock) extends AdminUserActions {

  private def doWander(wanderlust: Wanderlust): Future[(Seq[(User, Int)], Seq[(SocialUserInfo, Int)], Seq[(Library, User, Int)], Seq[(NormalizedURI, Int)], Seq[(String, Int)])] = {
    graphClient.wander(wanderlust).map { collisions =>

      val sortedUsers = db.readOnlyMaster { implicit session =>
        collisions.users.map { case (userId, count) => userRepo.get(userId) -> count }
      }.toSeq.sortBy(-_._2)

      val sortedSocialUsers = db.readOnlyReplica { implicit session =>
        collisions.socialUsers.map { case (socialUserInfoId, count) => socialUserRepo.get(socialUserInfoId) -> count }
      }.toSeq.sortBy(-_._2)

      val sortedLibraries = db.readOnlyReplica { implicit session =>
        collisions.libraries.flatMap {
          case (libraryId, count) =>
            val library = libraryRepo.get(libraryId)
            if (library.visibility != LibraryVisibility.PUBLISHED) {
              None
            } else {
              val owner = userRepo.get(library.ownerId)
              Some((library, owner, count))
            }
        }
      }.toSeq.sortBy(-_._3)

      val sortedUris = db.readOnlyReplica { implicit session =>
        collisions.uris.map { case (uriId, count) => uriRepo.get(uriId) -> count }
      }.filter(_._1.restriction.isEmpty).toSeq.sortBy(-_._2)

      val sortedExtras = collisions.extra.toSeq.sortBy(-_._2)

      (sortedUsers, sortedSocialUsers, sortedLibraries, sortedUris, sortedExtras)
    }
  }

  def wander() = AdminUserPage.async { implicit request =>
    request.request.method.toLowerCase match {
      case "get" => {
        graphClient.getGraphKinds().map { graphKinds =>
          val availableVertexKinds = (graphKinds.vertexKinds -- Set("Thread", "Tag")).toSeq.sorted
          val userVertexType = availableVertexKinds.find(_.toLowerCase.startsWith("user")).get
          val wanderlust = Wanderlust(userVertexType, request.userId.id)
          Ok(views.html.admin.graph.wanderView(availableVertexKinds, wanderlust, None, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty))
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

        val promisedResult = Promise[Result]()

        doWander(wanderlust).onComplete {

          case Failure(ex) => {
            val view = Ok(views.html.admin.graph.wanderView(availableVertexKinds, wanderlust, Some(Failure(ex)), Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty))
            promisedResult.success(view)
          }

          case Success((sortedUsers, sortedSocialUsers, sortedLibraries, sortedUris, sortedExtras)) => {

            val end = clock.now()
            val timing = end.getMillis - start.getMillis

            val view = Ok(views.html.admin.graph.wanderView(availableVertexKinds, wanderlust, Some(Success(timing)), sortedUsers, sortedSocialUsers, sortedLibraries, sortedUris, sortedExtras))
            promisedResult.success(view)
          }
        }

        promisedResult.future
      }
    }
  }

  def fromParisWithLove() = AdminUserPage.async { implicit request =>
    val start = clock.now()
    val promisedResult = Promise[Result]()

    doWander(Wanderlust.discovery(request.userId)).onComplete {
      case Failure(ex) =>
        val view = Ok(views.html.admin.graph.fromParisWithLove(request.user, Failure[Long](ex), Seq.empty))
        promisedResult.success(view)
      case Success((_, _, _, sortedUris, _)) =>
        val end = clock.now()
        val timing = end.getMillis - start.getMillis
        val view = Ok(views.html.admin.graph.fromParisWithLove(request.user, Success(timing), sortedUris))
        promisedResult.success(view)
    }

    promisedResult.future
  }

  def uriWandering(steps: Int) = AdminUserPage.async { implicit request =>
    val userId = request.userId
    val start = clock.now()

    graphClient.uriWander(userId, steps).map { uriScores =>
      val end = clock.now()
      val timing = end.getMillis - start.getMillis

      val sortedUris = db.readOnlyReplica { implicit session =>
        uriScores.map { case (uriId, count) => uriRepo.get(uriId) -> count }
      }.filter(_._1.restriction.isEmpty).toSeq.sortBy(-_._2)

      Ok(views.html.admin.graph.fromParisWithLove(request.user, Success(timing), sortedUris))
    }
  }
}
