package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.commanders.UserPersonaCommander
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.common.db.{ State, Id }
import play.api.libs.json.Json

class AdminPersonaController @Inject() (
    val userActionsHelper: UserActionsHelper,
    db: Database,
    personaRepo: PersonaRepo,
    userPersonaRepo: UserPersonaRepo,
    userPersonaCommander: UserPersonaCommander) extends AdminUserActions {

  def getAllPersonas() = AdminUserPage { implicit request =>
    val allPersonas = db.readOnlyReplica { implicit s =>
      personaRepo.all
    }
    Ok(Json.obj("personas" -> allPersonas.map(p => Json.obj(
      "id" -> p.id,
      "name" -> p.name,
      "state" -> p.state,
      "displayName" -> p.displayName,
      "iconPath" -> p.iconPath,
      "activeIconPath" -> p.activeIconPath
    ))))
  }

  def createPersona() = AdminUserPage { implicit request =>
    val personaOpt = request.body.asJson.map { json =>
      val name = (json \ "name").as[PersonaName] // note: can only create personas that are typesafe. Look at PersonaName
      val displayNameOpt = (json \ "displayName").as[Option[String]]
      val displayNamePluralOpt = (json \ "displayNamePlural").as[Option[String]]
      val iconPath = (json \ "iconPath").as[String]
      val activeIconPath = (json \ "activeIconPath").as[String]
      val displayName = displayNameOpt.getOrElse(name.value)
      val displayNamePlural = displayNamePluralOpt.getOrElse(displayName + "s")
      db.readWrite { implicit s =>
        personaRepo.getByName(name) match {
          case None =>
            personaRepo.save(Persona(
              name = name,
              displayName = displayName,
              displayNamePlural = displayNamePlural,
              iconPath = iconPath,
              activeIconPath = activeIconPath,
              state = PersonaStates.ACTIVE))
          case Some(p) =>
            personaRepo.save(p.copy(
              displayName = displayName,
              displayNamePlural = displayNamePlural,
              iconPath = iconPath,
              activeIconPath = activeIconPath,
              state = PersonaStates.ACTIVE))
        }
      }
    }

    personaOpt match {
      case None =>
        BadRequest(Json.obj("error" -> "unable_to_create_persona"))
      case Some(persona) =>
        NoContent
    }
  }

  def editPersona(id: Id[Persona]) = AdminUserPage { implicit request =>
    request.body.asJson.map { json =>
      val currentNameOpt = (json \ "name").asOpt[PersonaName]
      val displayNameOpt = (json \ "displayName").asOpt[String]
      val displayNamePluralOpt = (json \ "displayNamePlural").asOpt[String]
      val iconPathOpt = (json \ "iconPath").asOpt[String]
      val activeIconPathOpt = (json \ "activeIconPath").asOpt[String]
      val stateOpt = (json \ "state").asOpt[State[Persona]]

      db.readWrite { implicit s =>
        val currentPersona = personaRepo.get(id)
        val newCurrentName = currentNameOpt.getOrElse(currentPersona.name)
        val newDisplayName = displayNameOpt.getOrElse(currentPersona.displayName)
        val newDisplayNamePlural = displayNamePluralOpt.getOrElse(newDisplayName + "s")
        val newIconPath = iconPathOpt.getOrElse(currentPersona.iconPath)
        val newActiveIconPath = activeIconPathOpt.getOrElse(currentPersona.activeIconPath)
        val newState = stateOpt.getOrElse(currentPersona.state)

        personaRepo.save(currentPersona.copy(
          name = newCurrentName,
          displayName = newDisplayName,
          displayNamePlural = newDisplayNamePlural,
          iconPath = newIconPath,
          activeIconPath = newActiveIconPath,
          state = newState))
      }
    }
    NoContent
  }

}
