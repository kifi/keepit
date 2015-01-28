package com.keepit.commanders

import com.google.inject.{ Singleton, ImplementedBy, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._

@ImplementedBy(classOf[UserPersonaCommanderImpl])
trait UserPersonaCommander {
  def addPersonaForUser(userId: Id[User], persona: String)(implicit context: HeimdalContext): Option[(Persona, Library)]
  def addPersonasForUser(userId: Id[User], personas: Set[String])(implicit context: HeimdalContext): Map[Persona, Library]
  def removePersonaForUser(userId: Id[User], persona: String): Option[Persona]
  def removePersonasForUser(userId: Id[User], personas: Set[String]): Set[Persona]
}

@Singleton
class UserPersonaCommanderImpl @Inject() (
    db: Database,
    userPersonaRepo: UserPersonaRepo,
    personaRepo: PersonaRepo,
    libraryCommander: LibraryCommander,
    clock: Clock) extends UserPersonaCommander with Logging {

  def addPersonaForUser(userId: Id[User], persona: String)(implicit context: HeimdalContext): Option[(Persona, Library)] = {
    addPersonasForUser(userId, Set(persona)).headOption
  }

  def addPersonasForUser(userId: Id[User], personas: Set[String])(implicit context: HeimdalContext): Map[Persona, Library] = {
    val personasToPersist = db.readOnlyMaster { implicit s =>
      val currentPersonas = userPersonaRepo.getPersonasForUser(userId).toSet
      val personaNamesToAdd = personas diff currentPersonas.map(_.name)
      personaRepo.getByNames(personaNamesToAdd)
    }

    db.readWrite { implicit s =>
      personasToPersist.map {
        case (_, persona) =>
          userPersonaRepo.getByUserAndPersona(userId, persona.id.get) match {
            case None =>
              userPersonaRepo.save(UserPersona(userId = userId, personaId = persona.id.get, state = UserPersonaStates.ACTIVE))
            case Some(up) =>
              userPersonaRepo.save(up.copy(state = UserPersonaStates.ACTIVE))
          }
      }
    }

    // create libraries based on added personas
    personasToPersist.map {
      case (personaName, persona) =>
        val libraryAddReq = LibraryAddRequest(personaName, LibraryVisibility.PUBLISHED, None, personaName)
        (persona, libraryCommander.addLibrary(libraryAddReq, userId))
    }.collect {
      case (persona, Right(lib)) => (persona, lib)
    }.toMap
  }

  def removePersonaForUser(userId: Id[User], persona: String): Option[Persona] = {
    removePersonasForUser(userId, Set(persona)).headOption
  }

  def removePersonasForUser(userId: Id[User], personas: Set[String]): Set[Persona] = {
    val personasToRemove = db.readOnlyMaster { implicit s =>
      val currentPersonas = userPersonaRepo.getPersonasForUser(userId).toSet
      val personaNamesToRemove = personas intersect currentPersonas.map(_.name)
      personaRepo.getByNames(personaNamesToRemove)
    }

    db.readWrite { implicit s =>
      personasToRemove.map {
        case (_, persona) =>
          userPersonaRepo.getByUserAndPersona(userId, persona.id.get) match {
            case Some(up) if up.state == UserPersonaStates.ACTIVE =>
              userPersonaRepo.save(up.copy(state = UserPersonaStates.INACTIVE))
            case _ =>
          }
      }
    }
    personasToRemove.values.toSet
  }
}
