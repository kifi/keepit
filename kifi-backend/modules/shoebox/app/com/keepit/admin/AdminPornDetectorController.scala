package com.keepit.controllers.admin

import com.keepit.common.akka.SafeFuture
import com.keepit.common.util.Ord._
import com.keepit.common.core.optionExtensionOps
import com.keepit.rover.RoverServiceClient
import play.api.libs.iteratee.{ Concurrent, Enumerator }
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext
import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model._
import views.html
import scala.collection.mutable.ArrayBuffer

class AdminPornDetectorController @Inject() (
    rover: RoverServiceClient,
    db: Database,
    uriRepo: NormalizedURIRepo,
    bmRepo: KeepRepo,
    ktlRepo: KeepToLibraryRepo,
    val userActionsHelper: UserActionsHelper,
    implicit val executionContext: ExecutionContext) extends AdminUserActions {

  private def tokenize(query: String): Array[String] = {
    query.split("[^a-zA-Z0-9]").filter(!_.isEmpty).map { _.toLowerCase }
  }

  def index() = AdminUserPage { implicit request =>
    Ok(html.admin.pornDetector())
  }

  def detect() = AdminUserPage.async { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val text = body.get("query").get
    val numBlocks = tokenize(text).sliding(10, 10).size
    rover.detectPorn(text).map { badTexts =>
      val badInfo = badTexts.map { x => x._1 + " ---> " + x._2 }.mkString("\n")
      val msg = if (badTexts.isEmpty) "input text is clean" else s"${badTexts.size} out of $numBlocks blocks look suspicious:\n" + badInfo
      Ok(msg.replaceAll("\n", "\n<br>"))
    }
  }

  def pornUrisView(page: Int, publicOnly: Boolean) = AdminUserPage { implicit request =>
    val uris = db.readOnlyReplica { implicit s => uriRepo.getRestrictedURIs(Restriction.ADULT) }.sortBy(_.updatedAt)(descending)
    val PAGE_SIZE = 100

    val retUris = if (!publicOnly) uris.toArray else {
      val need = (page + 1) * PAGE_SIZE
      val buf = new ArrayBuffer[NormalizedURI]()
      var (i, cnt) = (0, 0)
      db.readOnlyReplica { implicit s =>
        while (i < uris.size && cnt < need) {
          val ktls = ktlRepo.adminGetByUri(uris(i).id.get)
          if (ktls.exists(!_.isPrivate)) {
            buf.append(uris(i))
            cnt += 1
          }
          i += 1
        }
      }
      buf.toArray
    }

    val pageCount = (retUris.length * 1.0 / PAGE_SIZE).ceil.toInt

    Ok(html.admin.pornUris(retUris.drop(page * PAGE_SIZE).take(PAGE_SIZE), retUris.length, page, pageCount, publicOnly))
  }

  def removeRestrictions() = AdminUserPage { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val ids = body.get("uriIds").get.split(",").map { id => Id[NormalizedURI](id.toLong) }
    db.readWrite { implicit s =>
      ids.foreach { id =>
        val uri = uriRepo.get(id)
        if (uri.restriction.safely.contains(Restriction.ADULT)) uriRepo.save(uri.copy(restriction = None))
      }
    }
    Ok(s"${ids.length} uris' adult restriction removed")
  }

  def whitelist() = AdminUserPage.async { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val whitelist = body.get("whitelist").get
    rover.whitelist(whitelist).map { cleaned =>
      Ok(s"following words are cleaned: " + cleaned)
    }
  }

  def getPornDetectorModel = AdminUserPage.async { implicit request =>
    val modelFuture = rover.getPornDetectorModel()
    for (model <- modelFuture) yield Ok(Json.toJson(model))
  }
}
