package com.keepit.controllers.admin

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.future

import org.joda.time._

import com.keepit.classify._
import com.keepit.common.analytics.{MongoEventStore, EventFamilies, MongoSelector}
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.model.{SliderRuleRepo, SliderRuleStates}
import com.keepit.model.{URLPattern, URLPatternRepo, URLPatternStates}
import com.mongodb.casbah.Imports._

import play.api.Play.current
import play.api.libs.json.{JsBoolean, JsArray, JsObject, Json}
import play.api.mvc.Action
import views.html

import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.google.inject.{Inject, Singleton}

@Singleton
class SliderAdminController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  sliderRuleRepo: SliderRuleRepo,
  urlPatternRepo: URLPatternRepo,
  domainTagRepo: DomainTagRepo,
  sensitivityUpdater: SensitivityUpdater,
  domainToTagRepo: DomainToTagRepo,
  domainRepo: DomainRepo,
  domainTagImporter: DomainTagImporter,
  mongoEventStore: MongoEventStore)
    extends AdminController(actionAuthenticator) {
  def getRules = AdminHtmlAction { implicit request =>
    val groupName = "default"
    val group = db.readOnly { implicit session =>
      sliderRuleRepo.getGroup(groupName)
    }
    Ok(html.admin.sliderRules(groupName, group.rules.map(r => r.name -> r).toMap))
  }

  def saveRules = AdminHtmlAction { implicit request =>
    val body = request.body.asFormUrlEncoded.get
    val groupName = body("group").head
    db.readWrite { implicit session =>
      sliderRuleRepo.getGroup(groupName).rules.foreach { rule =>
        val newRule = rule
          .withState(if (body.contains(rule.name)) SliderRuleStates.ACTIVE else SliderRuleStates.INACTIVE)
          .withParameters(body.get(rule.name + "Params").map { arr => JsArray(arr.map(Json.parse)) })
        if (newRule != rule) sliderRuleRepo.save(newRule)
      }
    }
    Redirect(routes.SliderAdminController.getRules)
  }

  def getPatterns = AdminHtmlAction { implicit request =>
    val patterns = db.readOnly { implicit session =>
      urlPatternRepo.all
    }
    Ok(html.admin.sliderPatterns(patterns))
  }

  def savePatterns = AdminHtmlAction { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_(0))
    db.readWrite { implicit session =>
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
    }
    Redirect(routes.SliderAdminController.getPatterns)
  }

  def getDomainTags = AdminHtmlAction { implicit request =>
    val tags = db.readOnly { implicit session =>
      domainTagRepo.all
    }
    Ok(html.admin.domainTags(tags))
  }

  def saveDomainTags = AdminHtmlAction { implicit request =>
    val tagIdValue = """sensitive_([0-9]+)""".r
    val sensitiveTags = request.body.asFormUrlEncoded.get.keys
      .collect { case tagIdValue(v) => Id[DomainTag](v.toInt) }.toSet
    val tagsToSave = db.readOnly { implicit s =>
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
        val domainIds = db.readOnly { implicit s =>
          domainToTagRepo.getByTag(tag.id.get).map(_.domainId)
        }
        db.readWrite { implicit s =>
          sensitivityUpdater.clearDomainSensitivity(domainIds)
        }
      }
    }
    Redirect(routes.SliderAdminController.getDomainTags)
  }

  def getDomainOverrides = AdminHtmlAction { implicit request =>
    val domains = db.readOnly { implicit session =>
      domainRepo.getOverrides()
    }
    Ok(html.admin.domains(domains))
  }

  def saveDomainOverrides = AdminJsonAction { implicit request =>
    val domainSensitiveMap = request.body.asFormUrlEncoded.get.map {
      case (k, vs) => k.toLowerCase -> (vs.head.toLowerCase == "true")
    }.toMap
    val domainsToRemove = db.readOnly { implicit session =>
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

  def refetchClassifications = /* TODO: AdminJson */Action { implicit request =>
    domainTagImporter.refetchClassifications()
    Ok(JsObject(Seq()))
  }

  def getImportEvents = AdminHtmlAction { implicit request =>
    import com.keepit.classify.DomainTagImportEvents._

    val selector = MongoSelector(EventFamilies.DOMAIN_TAG_IMPORT)
        .withMinDate(currentDateTime.minus(Period.months(1)))
    val failureSelector = MongoSelector(EventFamilies.EXCEPTION).withEventName(IMPORT_FAILURE)
        .withMinDate(currentDateTime.minus(Period.months(1)))
    val dbObjects = mongoEventStore.find("server", selector).toList
    val failureDbObjects = mongoEventStore.find("server", failureSelector).toList
    val events = (dbObjects ++ failureDbObjects).map { obj =>
      val meta = obj.getAs[DBObject]("metaData").get
      val createdAt = obj.getAs[DateTime]("createdAt").get
      val eventName = meta.getAs[String]("eventName").get
      val metaData = meta.getAs[DBObject]("metaData").orNull
      val description = eventName match {
        case IMPORT_START => "Full import started"
        case IMPORT_TAG_SUCCESS => "Tag %s imported (%d added, %d removed, %d total domains)".format(
          metaData.getAs[String]("tagName").get,
          metaData.getAs[Double]("numDomainsAdded").get.toInt,
          metaData.getAs[Double]("numDomainsRemoved").get.toInt,
          metaData.getAs[Double]("totalDomains").get.toInt)
        case IMPORT_SUCCESS => "Domains imported (%d added, %d removed, %d total domains)".format(
          metaData.getAs[Double]("numDomainsAdded").get.toInt,
          metaData.getAs[Double]("numDomainsRemoved").get.toInt,
          metaData.getAs[Double]("totalDomains").get.toInt)
        case REMOVE_TAG_SUCCESS => "Tag %s removed".format(metaData.getAs[String]("tagName").get)
        case IMPORT_FAILURE => metaData.getAs[String]("message").get
      }
      ImportEvent(createdAt, eventName, description)
    }.sortBy(_.createdAt).reverse
    Ok(html.admin.domainImportEvents(events))
  }
}

case class ImportEvent(createdAt: DateTime, eventType: String, description: String)

