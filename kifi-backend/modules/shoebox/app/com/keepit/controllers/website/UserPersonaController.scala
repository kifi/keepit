package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders.UserPersonaCommander
import com.keepit.common.controller.{ UserActionsHelper, UserActions, ShoeboxServiceController }
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
  val userActionsHelper: UserActionsHelper,
  heimdalContextFactory: HeimdalContextBuilderFactory,
  clock: Clock)
    extends UserActions with ShoeboxServiceController with Logging {

  def getAllPersonas() = UserAction { request =>
    val (allPersonas, userPersonas) = db.readOnlyMaster { implicit s =>
      val userPersonas = userPersonaRepo.getPersonasForUser(request.userId)
      val allPersonas = personaRepo.getByState(PersonaStates.ACTIVE)
      (allPersonas, userPersonas)
    }
    val personaMap = allPersonas.map { persona =>
      (persona.name.value, userPersonas.contains(persona))
    }.toMap
    Ok(Json.obj("personas" -> Json.toJson(personaMap)))
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
}
