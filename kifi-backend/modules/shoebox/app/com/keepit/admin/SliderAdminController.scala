package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.classify._
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.store.KifInstallationStore
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.{ SystemEventTypes, HeimdalContext, SystemEvent, HeimdalServiceClient }
import com.keepit.model._

import org.joda.time._

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ Json, JsArray, JsBoolean, JsObject }
import play.api.mvc.Action

import scala.concurrent.{ future, promise }

import views.html

class SliderAdminController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  clock: Clock,
  kifiInstallationRepo: KifiInstallationRepo,
  urlPatternRepo: URLPatternRepo,
  domainTagRepo: DomainTagRepo,
  domainClassifier: DomainClassifier,
  sensitivityUpdater: SensitivityUpdater,
  domainToTagRepo: DomainToTagRepo,
  domainRepo: DomainRepo,
  userRepo: UserRepo,
  kifiInstallationStore: KifInstallationStore,
  userValueRepo: UserValueRepo,
  domainTagImporter: DomainTagImporter,
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

  def getDomainTags = AdminUserPage { implicit request =>
    val tags = db.readOnlyReplica { implicit session =>
      domainTagRepo.all
    }
    Ok(html.admin.domainTags(tags))
  }

  def getClassifications(domain: Option[String]) = AdminUserPage.async { implicit request =>
    domain.map(domainClassifier.fetchTags)
      .getOrElse(promise[Seq[DomainTagName]].success(Seq()).future).map { tags =>
        val tagPairs = tags.map { t =>
          val tag = db.readOnlyReplica { implicit s => domainTagRepo.get(t) }
          (t.name, tag.map(_.sensitive.getOrElse(false)))
        }
        Ok(html.admin.classifications(domain, tagPairs))
      }
  }

  def saveDomainTags = AdminUserPage { implicit request =>
    val tagIdValue = """sensitive_([0-9]+)""".r
    val sensitiveTags = request.body.asFormUrlEncoded.get.keys
      .collect { case tagIdValue(v) => Id[DomainTag](v.toInt) }.toSet
    val tagsToSave = db.readOnlyReplica { implicit s =>
      domainTagRepo.all.map(tag => (tag, sensitiveTags contains tag.id.get)).collect {
        case (tag, sensitive) if tag.state == DomainTagStates.ACTIVE && tag.sensitive != Some(sensitive) =>
          tag.withSensitive(Some(sensitive))
      }
    }
    tagsToSave.foreach { tag =>
      db.readWrite { implicit s =>
        domainTagRepo.save(tag)
      }
      future {
        val domainIds = db.readOnlyMaster { implicit s =>
          domainToTagRepo.getByTag(tag.id.get).map(_.domainId)
        }
        db.readWrite { implicit s =>
          sensitivityUpdater.clearDomainSensitivity(domainIds)
        }
      }
    }
    Redirect(routes.SliderAdminController.getDomainTags)
  }

  def getDomainOverrides = AdminUserPage { implicit request =>
    val domains = db.readOnlyReplica { implicit session =>
      domainRepo.getOverrides()
    }
    Ok(html.admin.domains(domains))
  }

  def saveDomainOverrides = AdminUserAction { implicit request =>
    val domainSensitiveMap = request.body.asFormUrlEncoded.get.map {
      case (k, vs) => k.toLowerCase -> (vs.head.toLowerCase == "true")
    }.toMap
    val domainsToRemove = db.readOnlyReplica { implicit session =>
      domainRepo.getOverrides()
    }.filterNot(d => domainSensitiveMap.contains(d.hostname))

    db.readWrite { implicit s =>
      domainSensitiveMap.foreach {
        case (domainName, sensitive) if Domain.isValid(domainName) =>
          val domain = domainRepo.get(domainName)
            .getOrElse(Domain(hostname = domainName))
            .withManualSensitive(Some(sensitive))
          domainRepo.save(domain)
        case (domainName, _) =>
          log.debug("Invalid domain: %s" format domainName)
      }
      domainsToRemove.foreach { domain =>
        domainRepo.save(domain.withManualSensitive(None))
      }
    }
    Ok(JsObject(domainSensitiveMap map { case (s, b) => s -> JsBoolean(b) } toSeq))
  }

  def refetchClassifications = /* TODO: AdminJson */ Action { implicit request =>
    domainTagImporter.refetchClassifications()
    Ok(JsObject(Seq()))
  }

  def getImportEvents = AdminUserPage.async { implicit request =>
    import com.keepit.classify.DomainTagImportEvents._

    val eventsFuture = heimdal.getRawEvents[SystemEvent](50, 42000, SystemEventTypes.IMPORTED_DOMAIN_TAGS).map { rawEvents =>
      rawEvents.value.map { json =>
        val createdAt = DateTimeJsonFormat.reads(json \ "time" \ "$date").get
        val context = (json \ "context").as[HeimdalContext]
        val eventName = context.getSeq[String]("eventName").get.head
        val description = eventName match {
          case IMPORT_START => "Full import started"
          case IMPORT_TAG_SUCCESS => "Tag %s imported (%d added, %d removed, %d total domains)".format(
            context.getSeq[String]("tagName").get.head,
            context.getSeq[Double]("numDomainsAdded").get.head.toInt,
            context.getSeq[Double]("numDomainsRemoved").get.head.toInt,
            context.getSeq[Double]("totalDomains").get.head.toInt
          )
          case IMPORT_SUCCESS => "Domains imported (%d added, %d removed, %d total domains)".format(
            context.getSeq[Double]("numDomainsAdded").get.head.toInt,
            context.getSeq[Double]("numDomainsRemoved").get.head.toInt,
            context.getSeq[Double]("totalDomains").get.head.toInt
          )
          case REMOVE_TAG_SUCCESS => "Tag %s removed".format(context.getSeq[String]("tagName").get.head)
          case IMPORT_FAILURE => context.getSeq[String]("message").get.head
        }
        ImportEvent(createdAt, eventName, description)
      }.sortBy(_.createdAt).reverse
    }

    eventsFuture.map { events => Ok(html.admin.domainImportEvents(events)) }
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

  // for run when we launch libraries (10/2014)
  def setSiteShowLibraryIntro() = AdminUserAction { implicit request =>
    val userIds = db.readOnlyReplica { implicit session =>
      userRepo.getRecentActiveUsers(clock.now.minusYears(4)) filter { userId =>
        userValueRepo.getUserValue(userId, UserValueName.SITE_SHOW_LIBRARY_INTRO).isEmpty
      }
    }
    db.readWrite { implicit s =>
      userIds foreach { userId =>
        userValueRepo.setValue(userId, UserValueName.SITE_SHOW_LIBRARY_INTRO, true)
      }
    }
    Ok(Json.arr(userIds))
  }
}

case class ImportEvent(createdAt: DateTime, eventType: String, description: String)

