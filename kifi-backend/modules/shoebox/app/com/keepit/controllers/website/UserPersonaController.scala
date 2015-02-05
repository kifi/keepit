package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders.UserPersonaCommander
import com.keepit.common.controller.{ UserActionsHelper, UserActions, ShoeboxServiceController }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.heimdal.{ HeimdalContextBuilderFactory }
import com.keepit.model._
import play.api.libs.json.Json

class UserPersonaController @Inject() (
  db: Database,
  userPersonaCommander: UserPersonaCommander,
  personaRepo: PersonaRepo,
  userPersonaRepo: UserPersonaRepo,
  libraryRepo: LibraryRepo,
  val userActionsHelper: UserActionsHelper,
  heimdalContextFactory: HeimdalContextBuilderFactory,
  clock: Clock,
  implicit val config: PublicIdConfiguration)
    extends UserActions with ShoeboxServiceController with Logging {

  def getAllPersonas() = UserAction { request =>
    val (allPersonas, userPersonas) = db.readOnlyMaster { implicit s =>
      val userPersonas = userPersonaRepo.getPersonasForUser(request.userId)
      val allPersonas = personaRepo.getByState(PersonaStates.ACTIVE)
      (allPersonas, userPersonas)
    }
    val personaObjs = allPersonas.map { persona =>
      Json.obj(
        "id" -> persona.name,
        "displayName" -> persona.displayName,
        "displayNamePlural" -> persona.displayNamePlural,
        "selected" -> userPersonas.contains(persona),
        "iconPath" -> persona.iconPath,
        "activeIconPath" -> persona.activeIconPath)
    }
    Ok(Json.obj("personas" -> Json.toJson(personaObjs)))
  }

  def addPersona(personaStr: String) = UserAction { request =>
    implicit val context = heimdalContextFactory.withRequestInfoAndSource(request, KeepSource.site).build
    val personaName = PersonaName(personaStr)
    val (personaOpt, _) = userPersonaCommander.addPersonaForUser(request.userId, personaName)
    if (personaOpt.isEmpty)
      BadRequest(Json.obj("error" -> s"failed to add persona ${personaName} for ${request.userId}"))
    else
      NoContent
  }

  def removePersona(personaStr: String) = UserAction { request =>
    val personaName = PersonaName(personaStr)
    val personaOpt = userPersonaCommander.removePersonaForUser(request.userId, personaName)
    if (personaOpt.isEmpty)
      BadRequest(Json.obj("error" -> s"failed to remove persona ${personaName} for ${request.userId}"))
    else
      NoContent
  }

  def getDefaultKeepForPersona(personaStr: String) = UserAction { request =>
    val personaName = PersonaName(personaStr)

    db.readOnlyMaster { implicit s =>
      userPersonaRepo.getByUserAndPersonaName(request.userId, personaName)
    } match {
      case None => // user does not have this persona active
        BadRequest(Json.obj("error" -> "user does not have this persona active"))
      case Some(_) => // user does have this persona active
        val defaultKeep = PersonaName.personaKeeps.get(personaName).getOrElse(DefaultPersonaKeep.default)
        val libraryIdOpt = PersonaName.personaLibraryNames.get(personaName).map { libName =>
          db.readOnlyMaster { implicit s =>
            libraryRepo.getByNameAndUserId(request.userId, libName)
          }.map { lib =>
            Library.publicId(lib.id.get)
          }
        }.flatten

        Ok(Json.obj(
          "keep" -> Json.toJson(defaultKeep),
          "libraryId" -> libraryIdOpt
        ))
    }
  }

}
