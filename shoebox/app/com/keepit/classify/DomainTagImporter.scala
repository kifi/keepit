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
import com.keepit.common.analytics.{EventFamilies, Events, PersistEventPlugin}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBConnection
import com.keepit.common.logging.Logging
import com.keepit.common.time._

import akka.actor.Status.Failure
import akka.actor.{ActorSystem, Props, Actor}
import akka.dispatch.Future
import akka.pattern.ask
import akka.util.duration._
import play.api.libs.json.{JsArray, JsNumber, JsString, JsObject}
import play.api.libs.ws.WS

private case object RefetchAll
private case class ApplyTag(tagName: DomainTagName, domainNames: Seq[String])
private case class RemoveTag(tagName: DomainTagName)

object DomainTagImportEvents {
  // success
  val IMPORT_START = "importStart"
  val IMPORT_TAG_SUCCESS = "importTagSuccess"
  val IMPORT_SUCCESS = "importSuccess"
  val REMOVE_TAG_SUCCESS = "removeTagSuccess"

  // exceptions
  val IMPORT_TAG_FAILURE = "importTagFailure"
  val IMPORT_FAILURE = "importFailure"
  val APPLY_TAG_FAILURE = "applyTagFailure"
  val REMOVE_TAG_FAILURE = "removeTagFailure"
}

private[classify] class DomainTagImportActor(db: DBConnection, updater: SensitivityUpdater, clock: Provider[DateTime],
    domainRepo: DomainRepo, tagRepo: DomainTagRepo, domainToTagRepo: DomainToTagRepo,
    persistEventPlugin: PersistEventPlugin, settings: DomainTagImportSettings)
    extends Actor with Logging {

  import DomainTagImportEvents._

  private val FILE_FORMAT = "domains_%s.zip"

  private val DATE_FORMAT = DateTimeFormat.forPattern("yyyy_MM_dd_HH_mm_ss")
    .withLocale(Locale.ENGLISH).withZone(zones.PT)

  // the size of the group of domains to insert at a time
  private val GROUP_SIZE = 500

  protected def receive = {
    case RefetchAll =>
      try {
        val outputFilename = FILE_FORMAT.format(clock.get().toString(DATE_FORMAT))
        val outputPath = new URI("%s/%s".format(settings.localDir, outputFilename)).normalize.getPath
        log.info("refetching all domains to %s".format(outputPath))
        persistEvent(IMPORT_START, JsObject(Seq()))
        WS.url(settings.url).get().onRedeem { res =>
          val s = new FileOutputStream(outputPath)
          try {
            IOUtils.copy(res.getAHCResponse.getResponseBodyAsStream, s)
          } finally {
            s.close()
          }
          val zipFile = new ZipFile(outputPath)
          val results = (for (entry <- zipFile.entries if entry.getName.endsWith("/domains")) yield {
            val tagName = entry.getName.split("/", 3) match {
              case Array(_, categoryName, _) => DomainTagName(categoryName)
              case _ => throw new IllegalStateException("Invalid domain format: " + entry.getName)
            }
            if (!DomainTagName.isBlacklisted(tagName)) {
              val domains = Source.fromInputStream(zipFile.getInputStream(entry)).getLines()
                .map(_.toLowerCase.trim).filter { domain =>
                  val valid = Domain.isValid(domain)
                  if (!valid) log.debug("'%s' is not a valid domain!" format domain)
                  valid
                }.toSet.toSeq
              Some(withSensitivityUpdate(applyTagToDomains(tagName, domains)))
            } else None
          }).flatten
          persistEvent(IMPORT_SUCCESS, JsObject(Seq(
            "numDomainsAdded" -> JsNumber(results.map(_.added).sum),
            "numDomainsRemoved" -> JsNumber(results.map(_.removed).sum),
            "totalDomains" -> JsNumber(results.map(_.total).sum)
          )))
        }
      } catch {
        case e: Exception => failWithException(IMPORT_FAILURE, e)
      }
    case ApplyTag(tagName, domainNames) =>
      try {
        val TagApplyResult(tag, _, _, _) = withSensitivityUpdate(applyTagToDomains(tagName, domainNames))
        sender ! tag
      } catch {
        case e: Exception => failWithException(APPLY_TAG_FAILURE, e)
      }
    case RemoveTag(tagName) =>
      try {
        val result: Option[DomainTag] = withSensitivityUpdate {
          db.readWrite { implicit s =>
            tagRepo.get(tagName, excludeState = None).map { tag =>
              applyTagToDomains(tagName, Seq())
              tagRepo.save(tag.withState(DomainTagStates.INACTIVE))
            }
          }
        }
        result.foreach { tag =>
          persistEvent(REMOVE_TAG_SUCCESS, JsObject(Seq(
            "tagId" -> JsNumber(tag.id.get.id),
            "tagName" -> JsString(tag.name.name)
          )))
        }
        sender ! result
      } catch {
        case e: Exception => failWithException(REMOVE_TAG_FAILURE, e)
      }
  }

  private def persistEvent(eventName: String, metaData: JsObject) {
    persistEventPlugin.persist(Events.serverEvent(EventFamilies.DOMAIN_TAG_IMPORT, eventName, metaData))
  }

  private def failWithException(eventName: String, e: Exception) {
    log.error(e)
    persistEventPlugin.persist(Events.serverEvent(
      EventFamilies.EXCEPTION,
      eventName,
      JsObject(Seq(
        "message" -> JsString(e.getMessage),
        "stackTrace" -> JsString(e.getStackTraceString))
    )))
    sender ! Failure(e)
  }

  // execute a block of code and then update the changed domains
  private def withSensitivityUpdate[A](value: => A): A = {
    val startTime = currentDateTime
    val result = value
    db.readWrite { implicit s =>
      log.debug("Updating sensitivity for changed domains")
      updater.updateDomainsChangedSince(startTime)
    }
    result
  }

  private case class TagApplyResult(tag: DomainTag, added: Int, removed: Int, total: Int)

  private def applyTagToDomains(tagName: DomainTagName, domainNames: Seq[String]): TagApplyResult = {
    log.info("Loading domains for tag '%s' (%d domains)".format(tagName.name, domainNames.length))
    val tag = db.readWrite { implicit s =>
      tagRepo.get(tagName, excludeState = None) match {
        case Some(t) if t.state != DomainTagStates.ACTIVE => tagRepo.save(t.withState(DomainTagStates.ACTIVE))
        case Some(t) => t
        case None => tagRepo.save(DomainTag(name = tagName))
      }
    }
    val tagId = tag.id.get

    var added = 0
    var removed = 0

    // a mutable set containing all the ids we want to add
    val domainIdsToAdd = new mutable.HashSet[Id[Domain]]

    def findAffectedDomains(): Int = {
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
      domainTuples.size
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
                removed += 1
                domainToTagRepo.save(dtt.withState(DomainToTagStates.INACTIVE))
              case DomainToTagStates.INACTIVE if shouldBeActive =>
                added += 1
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
      added += domainIdsToAdd.size
    }

    val total = findAffectedDomains()
    findRelationshipsToUpdate()
    addNewRelationships()

    persistEvent(IMPORT_TAG_SUCCESS, JsObject(Seq(
      "tagId" -> JsNumber(tag.id.get.id),
      "tagName" -> JsString(tag.name.name),
      "numDomainsAdded" -> JsNumber(added),
      "numDomainsRemoved" -> JsNumber(removed),
      "totalDomains" -> JsNumber(total)
    )))

    TagApplyResult(tag, added, removed, total)
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
    persistEventPlugin: PersistEventPlugin, settings: DomainTagImportSettings)
    extends DomainTagImporter {

  private val actor = system.actorOf(Props {
    new DomainTagImportActor(db, updater, clock, domainRepo, tagRepo, domainToTagRepo, persistEventPlugin, settings)
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
