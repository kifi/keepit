package com.keepit.commanders

import com.google.inject.{ Singleton, ImplementedBy, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.curator.CuratorServiceClient
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
    curator: CuratorServiceClient,
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
    val personasToPersist = db.readOnlyMaster { implicit s =>
      val currentPersonas = userPersonaRepo.getPersonasForUser(userId).toSet
      val personaNamesToAdd = personas diff currentPersonas.map(_.name)
      personaRepo.getByNames(personaNamesToAdd)
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

    curator.ingestPersonaRecos(userId, personasToPersist.values.map { _.id.get }.toSeq.distinct).collect {
      case _ =>
        // create libraries based on added personas
        personasToPersist.map {
          case (personaName, persona) =>
            val defaultLibraryName = PersonaName.personaLibraryNames.get(personaName).getOrElse(personaName.value)
            val defaultLibrarySlug = LibrarySlug.generateFromName(defaultLibraryName)
            val libraryAddReq = LibraryAddRequest(name = defaultLibraryName, visibility = LibraryVisibility.PUBLISHED, slug = defaultLibrarySlug, kind = Some(LibraryKind.SYSTEM_PERSONA))
            (persona, libraryCommander.addLibrary(libraryAddReq, userId))
        }.collect {
          case (persona, Right(lib)) => (persona, Some(lib)) // library successfully created
          case (persona, Left(_)) => (persona, None) // library with name or slug already created
        }.toMap
    }
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
      val personaLibName = PersonaName.personaLibraryNames(persona.name) // find library with persona name
      val libOpt = db.readOnlyMaster { implicit s =>
        libraryRepo.getByNameAndUserId(userId, personaLibName)
      }
      val personaKeep = PersonaName.personaKeeps.get(persona.name).getOrElse(PersonaKeep.default) // find keep associated with persona
      (personaKeep, libOpt)
    }.getOrElse {
      (PersonaKeep.default, None)
    }
  }
}
