package com.keepit.classify

import scala.collection.JavaConversions.enumerationAsScalaIterator
import scala.collection.mutable
import scala.io.Source

import java.io.FileOutputStream
import java.net.URI
import java.util.Locale
import java.util.zip.ZipFile

import org.apache.poi.util.IOUtils
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import com.google.inject.{Provider, ImplementedBy, Inject}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBConnection
import com.keepit.common.logging.Logging
import com.keepit.common.time._

import scala.concurrent.{Await, Future}
import akka.actor.{ActorSystem, Props, Actor}
import akka.pattern.ask
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._
import play.api.libs.ws.WS

private case object RefetchAll
private case class ApplyTag(tagName: DomainTagName, domainNames: Seq[String])
private case class RemoveTag(tagName: DomainTagName)

private[classify] class DomainTagImportActor(db: DBConnection, updater: SensitivityUpdater, clock: Provider[DateTime],
    domainRepo: DomainRepo, tagRepo: DomainTagRepo, domainToTagRepo: DomainToTagRepo, settings: DomainTagImportSettings)
    extends Actor with Logging {

  private val FILE_FORMAT = "domains_%s.zip"

  private val DATE_FORMAT = DateTimeFormat.forPattern("yyyy_MM_dd_HH_mm_ss")
    .withLocale(Locale.ENGLISH).withZone(zones.PT)

  // the size of the group of domains to insert at a time
  private val GROUP_SIZE = 500

 def receive = {
    case RefetchAll =>
      val outputFilename = FILE_FORMAT.format(clock.get().toString(DATE_FORMAT))
      val outputPath = new URI("%s/%s".format(settings.localDir, outputFilename)).normalize.getPath
      log.info("refetching all domains to %s".format(outputPath))
      WS.url(settings.url).get().onRedeem { res =>
        val s = new FileOutputStream(outputPath)
        try {
          IOUtils.copy(res.getAHCResponse.getResponseBodyAsStream, s)
        } finally {
          s.close()
        }
        val zipFile = new ZipFile(outputPath)
        for (entry <- zipFile.entries if entry.getName.endsWith("/domains")) {
          val tagName = entry.getName.split("/", 3) match {
            case Array(_, categoryName, _) => DomainTagName(categoryName)
            case _ => throw new IllegalStateException("Invalid domain format: " + entry.getName)
          }
          if (!DomainTagName.isBlacklisted(tagName)) {
            val domains = Source.fromInputStream(zipFile.getInputStream(entry)).getLines
              .map(_.toLowerCase.trim).filter { domain =>
                val valid = Domain.isValid(domain)
                if (!valid) log.debug("'%s' is not a valid domain!" format domain)
                valid
              }.toSet.toSeq
            withSensitivityUpdate(applyTagToDomains(tagName, domains))
          }
        }
      }
    case ApplyTag(tagName, domainNames) =>
      sender ! withSensitivityUpdate(applyTagToDomains(tagName, domainNames))
    case RemoveTag(tagName) =>
      val result: Option[DomainTag] = withSensitivityUpdate {
        db.readWrite { implicit s =>
          tagRepo.get(tagName, excludeState = None).map { tag =>
            applyTagToDomains(tagName, Seq())
            tagRepo.save(tag.withState(DomainTagStates.INACTIVE))
          }
        }
      }
      sender ! result
  }

  // execute a block of code and then update the changed domains
  private def withSensitivityUpdate[A](value: => A): A = {
    try {
      val startTime = currentDateTime
      val result = value
      db.readWrite { implicit s =>
        log.debug("Updating sensitivity for changed domains")
        updater.updateDomainsChangedSince(startTime)
      }
      result
    } catch {
      case e: Throwable =>
        log.error(e)
        throw e
    }
  }

  private def applyTagToDomains(tagName: DomainTagName, domainNames: Seq[String]): DomainTag = {
    log.info("Loading domains for tag '%s' (%d domains)".format(tagName.name, domainNames.length))
    val tag = db.readWrite { implicit s =>
      tagRepo.get(tagName, excludeState = None) match {
        case Some(t) if t.state != DomainTagStates.ACTIVE => tagRepo.save(t.withState(DomainTagStates.ACTIVE))
        case Some(t) => t
        case None => tagRepo.save(DomainTag(name = tagName))
      }
    }
    val tagId = tag.id.get

    // a mutable set containing all the ids we want to add
    val domainIdsToAdd = new mutable.HashSet[Id[Domain]]

    def findAffectedDomains() {
      log.debug("'%s': getting set of domains to apply to".format(tagName.name))
      val domainTuples = db.readOnly { implicit s =>
        domainNames.map { hostname => (hostname, domainRepo.get(hostname)) }
      }
      domainTuples.grouped(GROUP_SIZE).foreach { batch =>
        db.readWrite { implicit s =>
          for ((hostname, domainOption) <- batch) {
            domainIdsToAdd += (domainOption match {
              case Some(domain) if domain.state != DomainStates.ACTIVE =>
                domainRepo.save(domain.withState(DomainStates.ACTIVE))
              case Some(domain) => domain
              case None => domainRepo.save(Domain(hostname = hostname))
            }).id.get
          }
        }
      }
    }

    def findRelationshipsToUpdate() {
      log.debug("'%s': finding domain tag relationships to update".format(tagName.name))
      val domainTagRels = db.readOnly { implicit s =>
        domainToTagRepo.getByTag(tagId, excludeState = None)
      }
      domainTagRels.grouped(GROUP_SIZE).foreach { batch =>
        db.readWrite { implicit s =>
          batch.foreach { dtt =>
            val shouldBeActive = domainIdsToAdd.contains(dtt.domainId)
            // this already exists in the db; just update it instead of adding it
            domainIdsToAdd -= dtt.domainId
            dtt.state match {
              case DomainToTagStates.ACTIVE if !shouldBeActive =>
                domainToTagRepo.save(dtt.withState(DomainToTagStates.INACTIVE))
              case DomainToTagStates.INACTIVE if shouldBeActive =>
                domainToTagRepo.save(dtt.withState(DomainToTagStates.ACTIVE))
              case _ =>
            }
          }
        }
      }
    }

    def addNewRelationships() {
      log.debug("'%s': adding new relationships".format(tagName.name))
      domainIdsToAdd.toStream.grouped(GROUP_SIZE).foreach { batch =>
        db.readWrite { implicit s =>
          domainToTagRepo.insertAll(batch.map { domainId =>
            DomainToTag(tagId = tagId, domainId = domainId)
          })
        }
      }
    }

    findAffectedDomains()
    findRelationshipsToUpdate()
    addNewRelationships()
    tag
  }
}

final case class DomainTagImportSettings(localDir: String = ".", url: String = "")

@ImplementedBy(classOf[DomainTagImporterImpl])
trait DomainTagImporter {
  def refetchClassifications()
  def removeTag(tagName: DomainTagName): Future[Option[DomainTag]]
  def applyTagToDomains(tagName: DomainTagName, domainNames: Seq[String]): Future[DomainTag]
}

class DomainTagImporterImpl @Inject()(domainRepo: DomainRepo, tagRepo: DomainTagRepo, domainToTagRepo: DomainToTagRepo,
    updater: SensitivityUpdater, clock: Provider[DateTime], system: ActorSystem, db: DBConnection,
    settings: DomainTagImportSettings)
    extends DomainTagImporter {

  private val actor = system.actorOf(Props {
    new DomainTagImportActor(db, updater, clock, domainRepo, tagRepo, domainToTagRepo, settings)
  })

  def refetchClassifications() {
    actor ! RefetchAll
  }

  def removeTag(tagName: DomainTagName): Future[Option[DomainTag]] = {
    actor.ask(RemoveTag(tagName))(1 minute).mapTo[Option[DomainTag]]
  }

  def applyTagToDomains(tagName: DomainTagName, domainNames: Seq[String]): Future[DomainTag] = {
    actor.ask(ApplyTag(tagName, domainNames))(1 minute).mapTo[DomainTag]
  }
}
