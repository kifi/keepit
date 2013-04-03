package com.keepit.classify

import scala.collection.JavaConversions.enumerationAsScalaIterator
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.Source

import java.io.FileOutputStream
import java.net.URI
import java.util.Locale
import java.util.zip.ZipFile

import org.apache.poi.util.IOUtils
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import com.google.inject.{Provider, ImplementedBy, Inject}

import com.keepit.common.actor.ActorFactory
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.analytics.{EventFamilies, Events, PersistEventPlugin}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckError, HealthcheckPlugin}
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{EmailAddresses, ElectronicMail, PostOffice}
import com.keepit.common.time._

import akka.actor.Status.Failure
import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.{JsNumber, JsString, JsObject}
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

private[classify] class DomainTagImportActor @Inject() (
  db: Database,
  updater: SensitivityUpdater,
  clock: Clock,
  domainRepo: DomainRepo,
  tagRepo: DomainTagRepo,
  domainToTagRepo: DomainToTagRepo,
  persistEventPlugin: PersistEventPlugin,
  settings: DomainTagImportSettings,
  postOffice: PostOffice,
  healthcheckPlugin: HealthcheckPlugin)
    extends FortyTwoActor(healthcheckPlugin) with Logging {

  import DomainTagImportEvents._

  private val FILE_FORMAT = "domains_%s.zip"

  private val DATE_FORMAT = DateTimeFormat.forPattern("yyyy_MM_dd_HH_mm_ss")
    .withLocale(Locale.ENGLISH).withZone(zones.PT)

  // the size of the group of domains to insert at a time
  private val GROUP_SIZE = 500

 def receive = {
    case RefetchAll =>
      try {
        val outputFilename = FILE_FORMAT.format(clock.now.toString(DATE_FORMAT))
        val outputPath = new URI(s"${settings.localDir}/$outputFilename").normalize.getPath
        log.info(s"refetching all domains to $outputPath")
        WS.url(settings.url).get().onSuccess { case res =>
          persistEvent(IMPORT_START, JsObject(Seq()))
          val startTime = currentDateTime
          postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.ENG,
            subject = "Domain import started", htmlBody = s"Domain import started at $startTime",
            category = PostOffice.Categories.ADMIN))
          val s = new FileOutputStream(outputPath)
          try {
            IOUtils.copy(res.getAHCResponse.getResponseBodyAsStream, s)
          } finally {
            s.close()
          }
          val zipFile = new ZipFile(outputPath)
          val results = zipFile.entries.toSeq.collect {
            case entry if entry.getName.endsWith("/domains") =>
              entry.getName.split("/", 2) match {
                case Array(categoryName, _) => (DomainTagName(categoryName), entry)
                case _ => throw new IllegalStateException("Invalid domain format: " + entry.getName)
              }
          }.collect {
            case (tagName, entry) if !DomainTagName.isBlacklisted(tagName) =>
              val domains = Source.fromInputStream(zipFile.getInputStream(entry)).getLines()
                .map(_.toLowerCase.trim).filter { domain =>
                  val valid = Domain.isValid(domain)
                  if (!valid) log.debug("'%s' is not a valid domain!" format domain)
                  valid
                }.toSet.toSeq
              withSensitivityUpdate(applyTagToDomains(tagName, domains))
          }
          val (added, removed, total) =
            (results.map(_.added).sum, results.map(_.removed).sum, results.map(_.total).sum)
          persistEvent(IMPORT_SUCCESS, JsObject(Seq(
            "numDomainsAdded" -> JsNumber(added),
            "numDomainsRemoved" -> JsNumber(removed),
            "totalDomains" -> JsNumber(total)
          )))
          val endTime = currentDateTime
          postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.ENG,
            subject = "Domain import finished",
            htmlBody =
                s"Domain import started at $startTime and completed successfully at $endTime " +
                s"with $added domain-tag pairs added, $removed domain-tag pairs removed, " +
                s"and $total total domain-tag pairs.",
            category = PostOffice.Categories.ADMIN))
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
    log.error(s"fail on event $eventName", e)
    healthcheckPlugin.addError(HealthcheckError(Some(e), None, None, Healthcheck.INTERNAL, Some(e.getMessage)))
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
      log.info("Clearing sensitivity for changed domains")
      updater.clearDomainsChangedSince(startTime)
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
      log.info("'%s': getting associated domains".format(tagName.name))
      val toSave = new mutable.ArrayBuffer[Domain]
      domainNames.grouped(GROUP_SIZE).foreach { batch =>
        val domains = db.readOnly { implicit s => domainRepo.getAllByName(batch) }
        domainIdsToAdd ++= domains.map(_.id.get)
        toSave ++= (batch.toSet -- domains.map(_.hostname)).map(h => Domain(hostname = h))
      }
      log.info("'%s': adding new domains".format(tagName.name))
      toSave.grouped(GROUP_SIZE).foreach { batch =>
        db.readWrite { implicit s =>
          batch.foreach { domain =>
            domainIdsToAdd += domainRepo.save(domain).id.get
          }
        }
      }
      domainNames.size
    }

    def findRelationshipsToUpdate() {
      log.info("'%s': getting associated domain tag relationships".format(tagName.name))
      val domainTagRels = db.readOnly { implicit s =>
        domainToTagRepo.getByTag(tagId, excludeState = None)
      }
      val toActivate = new mutable.ArrayBuffer[Id[DomainToTag]]
      val toDeactivate = new mutable.ArrayBuffer[Id[DomainToTag]]
      log.info("'%s': finding changed domain tag relationships".format(tagName.name))
      domainTagRels.grouped(GROUP_SIZE).foreach { batch =>
        db.readOnly { implicit s =>
          batch.foreach { dtt =>
            val shouldBeActive = domainIdsToAdd.contains(dtt.domainId)
            // this already exists in the db; just update it instead of adding it
            domainIdsToAdd -= dtt.domainId
            dtt.state match {
              case DomainToTagStates.ACTIVE if !shouldBeActive =>
                removed += 1
                toDeactivate += dtt.id.get
              case DomainToTagStates.INACTIVE if shouldBeActive =>
                added += 1
                toActivate += dtt.id.get
              case _ =>
            }
          }
        }
      }
      log.info("'%s': saving changed domain tag relationships".format(tagName.name))
      toActivate.grouped(GROUP_SIZE).foreach { batch =>
        db.readWrite { implicit s => domainToTagRepo.setState(batch, DomainToTagStates.ACTIVE) }
      }
      toDeactivate.grouped(GROUP_SIZE).foreach { batch =>
        db.readWrite { implicit s => domainToTagRepo.setState(batch, DomainToTagStates.INACTIVE) }
      }
    }

    def addNewRelationships() {
      log.info("'%s': adding new relationships".format(tagName.name))
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

class DomainTagImporterImpl @Inject() (
  actorFactory: ActorFactory[DomainTagImportActor])
    extends DomainTagImporter {

  private val actor = actorFactory.get()

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
