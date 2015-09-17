package com.keepit.commanders

import com.google.inject.{ Singleton, ImplementedBy, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.Future

@ImplementedBy(classOf[UserPersonaCommanderImpl])
trait UserPersonaCommander {
  def addPersonaForUser(userId: Id[User], persona: PersonaName)(implicit context: HeimdalContext): Future[(Option[Persona], Option[Library])]
  def addPersonasForUser(userId: Id[User], personas: Set[PersonaName])(implicit context: HeimdalContext): Future[Map[Persona, Option[Library]]]
  def removePersonaForUser(userId: Id[User], persona: PersonaName): Option[Persona]
  def removePersonasForUser(userId: Id[User], personas: Set[PersonaName]): Set[Persona]
  def getPersonaKeepAndLibrary(userId: Id[User]): (PersonaKeep, Option[Library])
}

@Singleton
class UserPersonaCommanderImpl @Inject() (
    db: Database,
    userPersonaRepo: UserPersonaRepo,
    personaRepo: PersonaRepo,
    libraryCommander: LibraryCommander,
    libraryRepo: LibraryRepo,
    clock: Clock) extends UserPersonaCommander with Logging {

  def addPersonaForUser(userId: Id[User], persona: PersonaName)(implicit context: HeimdalContext): Future[(Option[Persona], Option[Library])] = {
    addPersonasForUser(userId, Set(persona)).map { x =>
      x.headOption match {
        case Some(pair) => (Some(pair._1), pair._2)
        case _ => (None, None)
      }
    }
  }

  def addPersonasForUser(userId: Id[User], personas: Set[PersonaName])(implicit context: HeimdalContext): Future[Map[Persona, Option[Library]]] = {
    val (personasToPersist, hasNonDefaultLibrary) = db.readOnlyMaster { implicit s =>
      val currentPersonas = userPersonaRepo.getPersonasForUser(userId).toSet
      val personaNamesToAdd = personas diff currentPersonas.map(_.name)
      val personasToPersist = personaRepo.getByNames(personaNamesToAdd)
      val hasNonDefaultLibrary = libraryRepo.hasKindsByOwner(userId, Set(LibraryKind.USER_CREATED, LibraryKind.SYSTEM_PERSONA))
      (personasToPersist, hasNonDefaultLibrary)
    }

    def generatePersonaLibraries(personas: Map[PersonaName, Persona], hasNonDefaultLibrary: Boolean): Map[Persona, Option[Library]] = {
      personas.map {
        case (personaName, persona) =>
          val defaultLibraryName = Persona.libraryNames.getOrElse(personaName, personaName.value)
          val defaultLibrarySlug = LibrarySlug.generateFromName(defaultLibraryName)
          val libraryAddReq = LibraryCreateRequest(name = defaultLibraryName, visibility = LibraryVisibility.PUBLISHED, slug = defaultLibrarySlug, kind = Some(LibraryKind.SYSTEM_PERSONA))
          if (hasNonDefaultLibrary)
            (persona, Left("already_has_nondefault_libraries"))
          else
            (persona, libraryCommander.createLibrary(libraryAddReq, userId))

      }.collect {
        case (persona, Right(lib)) => (persona, Some(lib)) // library successfully created
        case (persona, Left(_)) => (persona, None) // library with name or slug already created
      }.toMap
    }

    db.readWrite { implicit s =>
      personasToPersist.map {
        case (_, persona) =>
          userPersonaRepo.getByUserAndPersonaId(userId, persona.id.get) match {
            case None =>
              userPersonaRepo.save(UserPersona(userId = userId, personaId = persona.id.get, state = UserPersonaStates.ACTIVE))
            case Some(up) =>
              userPersonaRepo.save(up.copy(state = UserPersonaStates.ACTIVE))
          }
      }
    }

    Future.successful(generatePersonaLibraries(personasToPersist, hasNonDefaultLibrary)) //no need for future here, though this code will die soon anyway
  }

  def removePersonaForUser(userId: Id[User], persona: PersonaName): Option[Persona] = {
    removePersonasForUser(userId, Set(persona)).headOption
  }

  def removePersonasForUser(userId: Id[User], personas: Set[PersonaName]): Set[Persona] = {
    val personasToRemove = db.readOnlyMaster { implicit s =>
      val currentPersonas = userPersonaRepo.getPersonasForUser(userId).toSet
      val personaNamesToRemove = personas intersect currentPersonas.map(_.name)
      personaRepo.getByNames(personaNamesToRemove)
    }

    db.readWrite { implicit s =>
      personasToRemove.map {
        case (_, persona) =>
          userPersonaRepo.getByUserAndPersonaId(userId, persona.id.get) match {
            case Some(up) if up.state == UserPersonaStates.ACTIVE =>
              userPersonaRepo.save(up.copy(state = UserPersonaStates.INACTIVE))
            case _ =>
          }
      }
    }
    personasToRemove.values.toSet
  }

  def getPersonaKeepAndLibrary(userId: Id[User]): (PersonaKeep, Option[Library]) = {
    val personaOpt = db.readOnlyMaster { implicit s =>
      userPersonaRepo.getFirstPersonaForUser(userId)
    }
    personaOpt.map { persona =>
      val personaLibName = Persona.libraryNames(persona.name) // find library with persona name
      val libOpt = db.readOnlyMaster { implicit s =>
        libraryRepo.getBySpaceAndName(LibrarySpace.fromUserId(userId), personaLibName)
      }
      val personaKeep = Persona.keeps.getOrElse(persona.name, Persona.defaultKeep) // find keep associated with persona
      (personaKeep, libOpt)
    }.getOrElse {
      (Persona.defaultKeep, None)
    }
  }
}
