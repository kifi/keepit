package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.commanders.UserPersonaCommander
import com.keepit.common.controller.{ UserActionsHelper, UserActions, ShoeboxServiceController }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.heimdal.{ HeimdalContextBuilderFactory }
import com.keepit.model._
import play.api.libs.json.Json

class MobileUserPersonaController @Inject() (
  db: Database,
  userPersonaCommander: UserPersonaCommander,
  personaRepo: PersonaRepo,
  userPersonaRepo: UserPersonaRepo,
  val userActionsHelper: UserActionsHelper,
  heimdalContextFactory: HeimdalContextBuilderFactory,
  clock: Clock)
    extends UserActions with ShoeboxServiceController with Logging {

  def getAllPersonas() = MaybeUserAction { request =>
    val (allPersonas, userPersonas) = db.readOnlyMaster { implicit s =>
      val allPersonas = personaRepo.getByState(PersonaStates.ACTIVE)
      val userPersonas = request.userIdOpt.map { userId =>
        userPersonaRepo.getPersonasForUser(userId)
      }.getOrElse(Seq.empty)
      (allPersonas, userPersonas)
    }

    val personaMap = allPersonas.map { persona =>
      (persona.name.value, userPersonas.contains(persona))
    }.toMap
    Ok(Json.obj("personas" -> Json.toJson(personaMap)))
  }

  def selectPersonas() = UserAction(parse.tolerantJson) { request =>
    implicit val context = heimdalContextFactory.withRequestInfoAndSource(request, KeepSource.mobile).build
    val persistPersonaNames = (request.body \ "personas").as[Seq[PersonaName]]
    val currentPersonaNames = db.readOnlyMaster { implicit s =>
      userPersonaRepo.getPersonasForUser(request.userId)
    }.map(_.name)

    val personasToAdd = persistPersonaNames diff currentPersonaNames
    val personasToRemove = currentPersonaNames diff persistPersonaNames

    val added = userPersonaCommander.addPersonasForUser(request.userId, personasToAdd.toSet)
    val removed = userPersonaCommander.removePersonasForUser(request.userId, personasToRemove.toSet)

    Ok(Json.obj(
      "added" -> added.keys.map(_.name).toSeq,
      "removed" -> removed.map(_.name).toSeq
    ))
  }
}
