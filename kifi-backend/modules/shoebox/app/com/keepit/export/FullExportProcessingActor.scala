package com.keepit.export

import java.io.FileOutputStream
import java.util.zip.{ ZipEntry, ZipOutputStream }

import com.google.inject.Inject
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.mail.{ SystemEmailAddress, ElectronicMail, LocalPostOffice }
import com.keepit.common.strings.StringSplit
import com.keepit.common.time._
import com.keepit.common.util.{ LinkElement, DescriptionElements }
import com.keepit.model._
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import com.kifi.juggle.ConcurrentTaskProcessingActor
import org.joda.time.Duration
import play.api.libs.iteratee.Iteratee
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Try, Failure, Success }

object FullExportProcessingConfig {
  val MAX_PROCESSING_DURATION = Duration.standardHours(2)
  val MIN_CONCURRENCY = 5
  val MAX_CONCURRENCY = 10
}

class FullExportProcessingActor @Inject() (
  db: Database,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  exportRequestRepo: FullExportRequestRepo,
  exportCommander: FullExportProducer,
  exportFormatter: FullExportFormatter,
  exportStore: S3KifiExportStore,
  userEmailAddressRepo: UserEmailAddressRepo,
  postOffice: LocalPostOffice,
  airbrake: AirbrakeNotifier,
  clock: Clock,
  implicit val executionContext: ExecutionContext,
  implicit val inhouseSlackClient: InhouseSlackClient,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends FortyTwoActor(airbrake) with ConcurrentTaskProcessingActor[Id[FullExportRequest]] {

  import FullExportProcessingConfig._

  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SHOEBOX)

  protected val minConcurrentTasks = MIN_CONCURRENCY
  protected val maxConcurrentTasks = MAX_CONCURRENCY

  protected def pullTasks(limit: Int): Future[Seq[Id[FullExportRequest]]] = {
    db.readWriteAsync { implicit session =>
      log.info(s"[FullExportProcessingActor] Pulling $limit tasks")
      val threshold = clock.now minus MAX_PROCESSING_DURATION
      val ids = exportRequestRepo.getRipeIds(limit, threshold)
      ids.filter(exportRequestRepo.markAsProcessing(_, threshold))
    }.andThen {
      case Success(tasks) =>
        if (tasks.nonEmpty) { slackLog.info(s"Pulling $limit export tasks yields $tasks") }
      case Failure(fail) =>
        airbrake.notify(fail)
        slackLog.error(s"Pulling $limit export tasks failed: ${fail.getMessage}")
    }
  }

  protected def processTasks(ids: Seq[Id[FullExportRequest]]): Map[Id[FullExportRequest], Future[Unit]] = {
    ids.map(id => id -> doExport(id)).toMap
  }

  private def doExport(id: Id[FullExportRequest]): Future[Unit] = Try {
    val request = db.readOnlyMaster { implicit s => exportRequestRepo.get(id) }
    slackLog.info(s"Processing export request $id for user ${request.userId}")
    val enum = exportCommander.fullExport(request.userId)
    val user = db.readOnlyMaster { implicit s => userRepo.get(request.userId) }
    val exportBase = s"[Kifi Export]${user.fullName.words.mkString("-")}-${user.externalId.id}"
    val exportFile = new File(exportBase + ".zip")
    val zip = {
      val zip = new ZipOutputStream(new FileOutputStream(exportFile))
      zip.putNextEntry(new ZipEntry(s"$exportBase/explorer/index.html"))
      zip.write(HackyExportAssets.index.getBytes("UTF-8"))
      zip.putNextEntry(new ZipEntry(s"$exportBase/explorer/export.js"))
      zip
    }
    exportFormatter.assignments(enum).run(Iteratee.fold(Set.empty[String]) {
      case (existingEntities, (entity, contents)) =>
        if (!existingEntities.contains(entity)) {
          zip.write {
            s"$entity = ${Json.prettyPrint(contents)}\n".getBytes("UTF-8")
          }
        }
        existingEntities + entity
    }).flatMap {
      case _ =>
        zip.closeEntry()
        zip.putNextEntry(new ZipEntry(s"$exportBase/importableBookmarks.html"))
        exportFormatter.bookmarks(enum).run(Iteratee.foreach { line =>
          zip.write((line + "\n").getBytes("UTF-8"))
        }).map { _ =>
          zip.closeEntry()
        }
    }.andThen {
      case Failure(fail) =>
        slackLog.error(s"[${clock.now}] Failed while writing user ${request.userId}'s export: ${fail.getMessage}")
        db.readWrite { implicit s => exportRequestRepo.markAsFailed(request.id.get, fail.getMessage) }
      case Success(_) =>
        zip.close()
        slackLog.info(s"[${clock.now}] Done writing user ${request.userId}'s export to $exportFile, uploading to S3")
        exportStore.store(exportFile).andThen {
          case Success(yay) =>
            slackLog.info(s"[${clock.now}] Uploaded $exportBase.zip, key = ${yay.getKey}")
            db.readWrite { implicit s => exportRequestRepo.markAsComplete(request.id.get, yay.getKey) }
            sendSuccessEmail(user, request)
          case Failure(aww) =>
            slackLog.error(s"[${clock.now}] Could not upload $exportBase.zip for userId=${request.userId} because ${aww.getMessage}")
            db.readWrite { implicit s => exportRequestRepo.markAsFailed(request.id.get, aww.getMessage) }
            airbrake.notify(s"export failed for userId=${request.userId}. reason: $aww")
        }
    }.map(_ => ())
  }.recoverWith {
    case exportFail =>
      slackLog.error(s"Failed processing export $id:", exportFail.getMessage)
      Failure(exportFail)
  }.get

  private def sendSuccessEmail(user: User, request: FullExportRequest): Unit = {
    import DescriptionElements._
    db.readWrite { implicit s =>
      exportRequestRepo.getByUser(user.id.get).flatMap(_.notifyEmail).foreach { userEmailAddress =>
        val body = DescriptionElements.unlines(Seq(DescriptionElements("Visit", "www.kifi.com/keepmykeeps" --> LinkElement("https://www.kifi.com/keepmykeeps"), "to download the file for your export."),
          DescriptionElements(
            "The Kifi service has shut down, but your export file will be available for several weeks. The latest status is available on", "Kifi.com" --> LinkElement("https://www.kifi.com"),
            "and you can", "learn more on our blog" --> LinkElement("https://medium.com/@kifi/f1cd2f2e116c"), ".")))
        postOffice.sendMail(ElectronicMail(
          from = SystemEmailAddress.NOTIFICATIONS,
          fromName = Some("Kifi"),
          to = Seq(userEmailAddress),
          subject = s"Your Kifi export is ready!",
          htmlBody = DescriptionElements.formatAsHtml(body).body,
          textBody = Some(DescriptionElements.formatPlain(body)),
          category = NotificationCategory.User.EXPORT_READY
        ))
      }
    }
  }
}
