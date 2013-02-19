package com.keepit.controllers.admin

import com.keepit.classify._
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBConnection
import com.keepit.inject.inject
import com.keepit.model.{SliderRuleRepo, SliderRuleStates}
import com.keepit.model.{URLPattern, URLPatternRepo, URLPatternStates}

import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.json.{JsBoolean, JsArray, JsObject, Json}
import play.api.mvc.Action

object SliderAdminController extends FortyTwoController {

  def getRules = AdminHtmlAction { implicit request =>
    val groupName = "default"
    val group = inject[DBConnection].readOnly { implicit session =>
      inject[SliderRuleRepo].getGroup(groupName)
    }
    Ok(views.html.sliderRules(groupName, group.rules.map(r => r.name -> r).toMap))
  }

  def saveRules = AdminHtmlAction { implicit request =>
    val body = request.body.asFormUrlEncoded.get
    val groupName = body("group").head
    inject[DBConnection].readWrite { implicit session =>
      val repo = inject[SliderRuleRepo]
      repo.getGroup(groupName).rules.foreach { rule =>
        val newRule = rule
          .withState(if (body.contains(rule.name)) SliderRuleStates.ACTIVE else SliderRuleStates.INACTIVE)
          .withParameters(body.get(rule.name + "Params").map { arr => JsArray(arr.map(Json.parse)) })
        if (newRule != rule) repo.save(newRule)
      }
    }
    Redirect(routes.SliderAdminController.getRules)
  }

  def getPatterns = AdminHtmlAction { implicit request =>
    val patterns = inject[DBConnection].readOnly { implicit session =>
      inject[URLPatternRepo].all
    }
    Ok(views.html.sliderPatterns(patterns))
  }

  def savePatterns = AdminHtmlAction { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_(0))
    inject[DBConnection].readWrite { implicit session =>
      val repo = inject[URLPatternRepo]
      for (key <- body.keys.filter(_.startsWith("pattern_")).map(_.substring(8))) {
        val id = Id[URLPattern](key.toLong)
        val oldPat = repo.get(id)
        val newPat = oldPat
          .withPattern(body("pattern_" + key))
          .withExample(Some(body("example_" + key)).filter(!_.isEmpty))
          .withState(if (body.contains("active_" + key)) URLPatternStates.ACTIVE else URLPatternStates.INACTIVE)
        if (newPat != oldPat) {
          repo.save(newPat)
        }
      }
      val newPat = body("new_pattern")
      if (!newPat.isEmpty) {
        repo.save(URLPattern(None, newPat,
          Some(body("new_example")).filter(!_.isEmpty),
          state = if (body.contains("new_active")) URLPatternStates.ACTIVE else URLPatternStates.INACTIVE))
      }
    }
    Redirect(routes.SliderAdminController.getPatterns)
  }

  def getDomainTags = AdminHtmlAction { implicit request =>
    val tags = inject[DBConnection].readOnly { implicit session =>
      inject[DomainTagRepo].all
    }
    Ok(views.html.domainTags(tags))
  }

  def saveDomainTags = AdminHtmlAction { implicit request =>
    val db = inject[DBConnection]
    val sensitivityUpdater = inject[SensitivityUpdater]
    val domainToTagRepo = inject[DomainToTagRepo]
    val domainRepo = inject[DomainRepo]
    val tagRepo = inject[DomainTagRepo]

    val tagIdValue = """sensitive_([0-9]+)""".r
    val sensitiveTags = request.body.asFormUrlEncoded.get.keys
      .collect { case tagIdValue(v) => Id[DomainTag](v.toInt) }.toSet
    val tagsToSave = db.readOnly { implicit s =>
      tagRepo.all.map(tag => (tag, sensitiveTags contains tag.id.get)).collect {
        case (tag, sensitive) if tag.state == DomainTagStates.ACTIVE && tag.sensitive != Some(sensitive) =>
          tag.withSensitive(Some(sensitive))
      }
    }
    tagsToSave.foreach { tag =>
      db.readWrite { implicit s =>
        inject[DomainTagRepo].save(tag)
      }
      Akka.future {
        val domainIds = db.readOnly { implicit s =>
          domainToTagRepo.getByTag(tag.id.get).map(_.domainId)
        }
        domainIds.grouped(1000).foreach { ids =>
          db.readWrite { implicit s =>
            ids.map(domainRepo.get).foreach(sensitivityUpdater.updateSensitivity)
          }
        }
      }
    }
    Redirect(routes.SliderAdminController.getDomainTags)
  }

  def getDomainOverrides = AdminHtmlAction { implicit request =>
    val domains = inject[DBConnection].readOnly { implicit session =>
      inject[DomainRepo].getOverrides()
    }
    Ok(views.html.domains(domains))
  }

  def saveDomainOverrides = AuthenticatedJsonAction { implicit request =>
    val domainRepo = inject[DomainRepo]

    val domainSensitiveMap = request.body.asFormUrlEncoded.flatten.map {
      case (k, vs) => k.toLowerCase -> (vs.head.toLowerCase == "true")
    }.toMap
    val domainsToRemove = inject[DBConnection].readOnly { implicit session =>
      inject[DomainRepo].getOverrides()
    }.filterNot(d => domainSensitiveMap.contains(d.hostname))

    inject[DBConnection].readWrite { implicit s =>
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

  def refetchClassifications = Action { implicit request =>
    inject[DomainTagImporter].refetchClassifications()
    Ok(JsObject(Seq()))
  }
}
