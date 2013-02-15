package com.keepit.classify

import com.google.inject.{ImplementedBy, Inject}
import com.keepit.common.db.slick.DBConnection

import scala.concurrent.{Await, Future}
import akka.actor.{ActorSystem, Props, Actor}
import akka.japi.Option
import akka.pattern.ask
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._

private case class ApplyTag(tagName: DomainTagName, domainNames: Iterable[String])
private case class RemoveTag(tagName: DomainTagName)

private[classify] class DomainTagImportActor(db: DBConnection, updater: SensitivityUpdater,
    domainRepo: DomainRepo, tagRepo: DomainTagRepo, domainToTagRepo: DomainToTagRepo) extends Actor {

  def receive = {
    case ApplyTag(tagName, domainNames) =>
      sender ! applyTagToDomains(tagName, domainNames)
    case RemoveTag(tagName) =>
      val result: Option[DomainTag] = db.readWrite { implicit s =>
        tagRepo.get(tagName, excludeState = None).map { tag =>
          applyTagToDomains(tagName, Seq())
          tagRepo.save(tag.withState(DomainTagStates.INACTIVE))
        }
      }
      sender ! result
  }

  def applyTagToDomains(tagName: DomainTagName, domainNames: Iterable[String]): DomainTag = {
    db.readWrite { implicit s =>
      val tag = tagRepo.get(tagName, excludeState = None) match {
        case Some(tag) if tag.state != DomainTagStates.ACTIVE => tagRepo.save(tag.withState(DomainTagStates.ACTIVE))
        case Some(tag) => tag
        case None => tagRepo.save(DomainTag(name = tagName))
      }
      val tagId = tag.id.get
      val existingDomainToTags = domainToTagRepo.getByTag(tagId, excludeState = None)

      // get domains, saving ones that don't exist exist
      val domainsAppliedTo = domainNames.toSet.map { hostname: String =>
        domainRepo.get(hostname) match {
          case Some(domain) if domain.state != DomainTagStates.ACTIVE =>
            domainRepo.save(domain.withState(DomainStates.ACTIVE))
          case Some(domain) => domain
          case None => domainRepo.save(Domain(hostname = hostname))
        }
      }
      val domainIdsAppliedTo = domainsAppliedTo.map(_.id.get)

      // figure out what changes we need to make; construct a list of tuples containing the old and new value
      val oldNewTuples = existingDomainToTags.map { dtt =>
        val shouldBeActive = domainIdsAppliedTo.contains(dtt.domainId)
        (dtt, dtt.state match {
          case DomainToTagStates.ACTIVE if !shouldBeActive =>
            domainToTagRepo.save(dtt.withState(DomainToTagStates.INACTIVE))
          case DomainToTagStates.INACTIVE if shouldBeActive =>
            domainToTagRepo.save(dtt.withState(DomainToTagStates.ACTIVE))
          case _ => dtt
        })
      }

      val activeDomainIds = oldNewTuples.collect { case (a, b) if b.state == DomainTagStates.ACTIVE => b.domainId }
      val removedDomains = oldNewTuples.collect {
        case (a, b) if a.state == DomainTagStates.ACTIVE && b.state == DomainTagStates.INACTIVE =>
          domainRepo.get(b.domainId)
      }

      // insert new domain tags with the domain ids and the current tag id
      domainToTagRepo.insertAll((domainIdsAppliedTo -- activeDomainIds).toSeq.map { domainId =>
        DomainToTag(tagId = tagId, domainId = domainId)
      })

      // update the sensitivity for all changed domains
      updater.updateSensitivity(domainsAppliedTo.toSeq ++ removedDomains)

      tag
    }
  }
}

@ImplementedBy(classOf[DomainTagImporterImpl])
trait DomainTagImporter {
  def removeTag(tagName: DomainTagName): Future[Option[DomainTag]]
  def applyTagToDomains(tagName: DomainTagName, domainNames: Iterable[String]): Future[DomainTag]
}

class DomainTagImporterImpl @Inject()(domainRepo: DomainRepo, tagRepo: DomainTagRepo, domainToTagRepo: DomainToTagRepo,
    updater: SensitivityUpdater, system: ActorSystem, db: DBConnection) extends DomainTagImporter {

  private val actor = system.actorOf(Props {
    new DomainTagImportActor(db, updater, domainRepo, tagRepo, domainToTagRepo)
  })

  def removeTag(tagName: DomainTagName): Future[Option[DomainTag]] = {
    actor.ask(RemoveTag(tagName))(1 minute).mapTo[Option[DomainTag]]
  }

  def applyTagToDomains(tagName: DomainTagName, domainNames: Iterable[String]): Future[DomainTag] = {
    actor.ask(ApplyTag(tagName, domainNames))(1 minute).mapTo[DomainTag]
  }
}
