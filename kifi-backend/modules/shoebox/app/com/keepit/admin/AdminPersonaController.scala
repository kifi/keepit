package com.keepit.admin

import com.google.inject.Inject
import com.keepit.commanders.UserPersonaCommander
import com.keepit.common.controller.{AdminUserActions, UserActionsHelper}
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.common.db.Id
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
      val name = (json \ "name").as[PersonaName]
      val displayNameOpt = (json \ "displayName").as[Option[String]]
      val iconPath = (json \ "iconPath").as[String]
      val activeIconPath = (json \ "activeIconPath").as[String]
      db.readWrite { implicit s =>
        personaRepo.getByName(name) match {
          case None =>
            personaRepo.save(Persona(
              name = name,
              displayName = displayNameOpt.getOrElse(name.value),
              iconPath = iconPath,
              activeIconPath = activeIconPath,
              state = PersonaStates.ACTIVE))
          case Some(p) =>
            personaRepo.save(p.copy(
              displayName = displayNameOpt.getOrElse(name.value),
              iconPath = iconPath,
              activeIconPath = activeIconPath,
              state = PersonaStates.ACTIVE))
        }

      }
    }
    personaOpt match {
      case None => BadRequest(Json.obj("error" -> "unable_to_create_persona"))
      case Some(persona) => Ok(Json.obj("persona" -> Json.toJson(persona)))
    }
  }

  def editPersona(id: Id[Persona]) = AdminUserPage { implicit request =>
    request.body.asJson.map { json =>
      val currentPersonaName = PersonaName(name)
      val displayNameOpt = (json \ "displayName").as[Option[String]]
      val iconPathOpt = (json \ "iconPath").as[Option[String]]
      val activeIconPathOpt = (json \ "activeIconPath").as[Option[String]]

      db.readWrite { implicit s =>
        personaRepo.getByName(currentPersonaName).map { currentPersona =>
          val newDisplayName = displayNameOpt.getOrElse(currentPersona.displayName)
          val newIconPath = iconPathOpt.getOrElse(currentPersona.iconPath)
          val newActiveIconPath = activeIconPathOpt.getOrElse(currentPersona.activeIconPath)
          personaRepo.save(currentPersona.copy(displayName = newDisplayName, iconPath = newIconPath, activeIconPath = newActiveIconPath))
        }
      }
    }
    NoContent
  }

  def deletePersona(id: Id[Persona]) = AdminUserPage { implicit request =>
    db.readWrite { implicit s =>
      personaRepo.getByName(PersonaName(name)).map { persona =>
        personaRepo.save(persona.copy(state = PersonaStates.INACTIVE))
      }
    }
    NoContent
  }

}

