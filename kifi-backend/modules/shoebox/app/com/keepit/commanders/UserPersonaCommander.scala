package com.keepit.commanders

import com.google.inject.{ Provider, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._

class UserPersonaCommander @Inject() (
    db: Database,
    userPersonaRepo: UserPersonaRepo,
    personaRepo: PersonaRepo,
    libraryCommander: LibraryCommander,
    clock: Clock) extends Logging {

  def getPersonasForUser(userId: Id[User]): Seq[Persona] = {
    db.readOnlyMaster { implicit s =>
      val personaIds = userPersonaRepo.getUserPersonaIds(userId).toSet
      personaRepo.getPersonasByIds(personaIds)
    }
  }

  def addPersonasForUser(userId: Id[User], personas: Set[String])(implicit context: HeimdalContext): (Seq[Persona], Seq[Library]) = {
    val (newPersonas, currentPersonaIds) = db.readOnlyMaster { implicit s =>
      val newPersonas = personaRepo.getByNames(personas)
      val currentPersonaIds = userPersonaRepo.getUserPersonaIds(userId)
      (newPersonas, currentPersonaIds)
    }
    val personasToPersist = newPersonas.filterNot(p => currentPersonaIds.contains(p.id.get))
    db.readWrite { implicit s =>
      personasToPersist.map { persona =>
        userPersonaRepo.getByUserAndPersona(userId, persona.id.get) match {
          case None =>
            userPersonaRepo.save(UserPersona(userId = userId, personaId = persona.id.get, state = UserPersonaStates.ACTIVE))
          case Some(up) =>
            userPersonaRepo.save(up.copy(state = UserPersonaStates.ACTIVE))
        }
      }
    }

    // create libraries based on added personas
    val librariesAdded = personasToPersist.map { persona =>
      val libraryAddReq = LibraryAddRequest(persona.name, LibraryVisibility.PUBLISHED, None, persona.name)
      libraryCommander.addLibrary(libraryAddReq, userId)
    }.collect {
      case Right(lib) => lib
    }
    (personasToPersist, librariesAdded)
  }

  def removePersonasForUser(userId: Id[User], personas: Set[String]): Seq[Persona] = {
    val (newPersonas, currentPersonaIds) = db.readOnlyMaster { implicit s =>
      val newPersonas = personaRepo.getByNames(personas)
      val currentPersonaIds = userPersonaRepo.getUserPersonaIds(userId)
      (newPersonas, currentPersonaIds)
    }
    val personasToRemove = newPersonas.filter(p => currentPersonaIds.contains(p.id.get))
    db.readWrite { implicit s =>
      personasToRemove.map { persona =>
        userPersonaRepo.getByUserAndPersona(userId, persona.id.get) match {
          case Some(up) if up.state == UserPersonaStates.ACTIVE =>
            userPersonaRepo.save(up.copy(state = UserPersonaStates.INACTIVE))
          case _ =>
        }
      }
    }
    personasToRemove
  }
}
