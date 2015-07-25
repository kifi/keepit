package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.classify._
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.store.KifiInstallationStore
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.{ SystemEventTypes, HeimdalContext, SystemEvent, HeimdalServiceClient }
import com.keepit.model._

import org.joda.time._

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ Json, JsArray, JsBoolean, JsObject }
import play.api.mvc.Action

import scala.concurrent.{ Future, future, promise }

import views.html

class SliderAdminController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  clock: Clock,
  kifiInstallationRepo: KifiInstallationRepo,
  urlPatternRepo: URLPatternRepo,
  userRepo: UserRepo,
  kifiInstallationStore: KifiInstallationStore,
  userValueRepo: UserValueRepo,
  heimdal: HeimdalServiceClient,
  eliza: ElizaServiceClient)
    extends AdminUserActions {

  def getPatterns = AdminUserPage { implicit request =>
    val patterns = db.readOnlyReplica { implicit session =>
      urlPatternRepo.all
    }
    Ok(html.admin.sliderPatterns(patterns))
  }

  def savePatterns = AdminUserPage { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_(0))
    val patterns = db.readWrite { implicit session =>
      for (key <- body.keys.filter(_.startsWith("pattern_")).map(_.substring(8))) {
        val id = Id[URLPattern](key.toLong)
        val oldPat = urlPatternRepo.get(id)
        val newPat = oldPat
          .withPattern(body("pattern_" + key))
          .withExample(Some(body("example_" + key)).filter(!_.isEmpty))
          .withState(if (body.contains("active_" + key)) URLPatternStates.ACTIVE else URLPatternStates.INACTIVE)
        if (newPat != oldPat) {
          urlPatternRepo.save(newPat)
        }
      }
      val newPat = body("new_pattern")
      if (!newPat.isEmpty) {
        urlPatternRepo.save(URLPattern(None, newPat,
          Some(body("new_example")).filter(!_.isEmpty),
          state = if (body.contains("new_active")) URLPatternStates.ACTIVE else URLPatternStates.INACTIVE))
      }
      urlPatternRepo.getActivePatterns()
    }
    eliza.sendToAllUsers(Json.arr("url_patterns", patterns))
    Redirect(routes.SliderAdminController.getPatterns)
  }

  def getVersionForm = AdminUserPage { implicit request =>
    val details = kifiInstallationStore.getRaw()

    val installations = db.readOnlyMaster { implicit session =>
      kifiInstallationRepo.getLatestActiveExtensionVersions(20)
    }
    Ok(html.admin.versionForm(installations, details))
  }

  def killVersion(ver: String) = AdminUserAction { implicit request =>
    val details = kifiInstallationStore.getRaw()
    val newDetails = details.copy(killed = details.killed :+ KifiExtVersion(ver))
    kifiInstallationStore.set(newDetails)
    Ok("0")
  }

  def unkillVersion(ver: String) = AdminUserAction { implicit request =>
    val details = kifiInstallationStore.getRaw()
    val version = KifiExtVersion(ver)
    val newDetails = details.copy(killed = details.killed.filterNot(_.compare(version) == 0))
    kifiInstallationStore.set(newDetails)
    Ok("0")
  }

  def goldenVersion(ver: String) = AdminUserAction { implicit request =>
    val details = kifiInstallationStore.getRaw()
    val newDetails = details.copy(gold = KifiExtVersion(ver))
    kifiInstallationStore.set(newDetails)
    Ok("0")
  }

  def broadcastLatestVersion(ver: String) = AdminUserAction { implicit request =>
    eliza.sendToAllUsers(Json.arr("version", ver))
    Ok(Json.obj("version" -> ver))
  }
}

case class ImportEvent(createdAt: DateTime, eventType: String, description: String)

