package com.keepit.export

import java.io.FileOutputStream
import java.util.zip.{ ZipEntry, ZipOutputStream }

import com.google.inject.Inject
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.core._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.strings.StringSplit
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import com.kifi.juggle.ConcurrentTaskProcessingActor
import org.joda.time.Duration
import play.api.libs.iteratee.Iteratee
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object FullExportProcessingConfig {
  val MAX_PROCESSING_DURATION = Duration.standardHours(4)
  val MIN_CONCURRENCY = 0
  val MAX_CONCURRENCY = 1
}

class FullExportProcessingActor @Inject() (
  db: Database,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  exportRequestRepo: FullExportRequestRepo,
  exportCommander: FullExportProducer,
  exportFormatter: FullExportFormatter,
  exportStore: S3KifiExportStore,
  airbrake: AirbrakeNotifier,
  clock: Clock,
  implicit val executionContext: ExecutionContext,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends FortyTwoActor(airbrake) with ConcurrentTaskProcessingActor[Id[FullExportRequest]] {
  import FullExportProcessingConfig._

  val slackLog = new SlackLog(InhouseSlackChannel.TEST_RYAN)

  protected val minConcurrentTasks = MIN_CONCURRENCY
  protected val maxConcurrentTasks = MAX_CONCURRENCY

  protected def pullTasks(limit: Int): Future[Seq[Id[FullExportRequest]]] = {
    db.readWriteAsync { implicit session =>
      val threshold = clock.now minus MAX_PROCESSING_DURATION
      val ids = exportRequestRepo.getRipeIds(limit, threshold)
      ids.filter(exportRequestRepo.markAsProcessing(_, threshold))
    }.andThen {
      case Success(tasks) =>
        slackLog.info(s"Pulling $limit export tasks yields $tasks")
      case Failure(fail) =>
        airbrake.notify(fail)
        slackLog.error(s"Pulling $limit export tasks failed: ${fail.getMessage}")
    }
  }

  protected def processTasks(ids: Seq[Id[FullExportRequest]]): Map[Id[FullExportRequest], Future[Unit]] = {
    ids.map(id => id -> doExport(id)).toMap
  }

  private def doExport(id: Id[FullExportRequest]): Future[Unit] = {
    slackLog.info(s"Processing export request $id")
    val request = db.readOnlyMaster { implicit s => exportRequestRepo.get(id) }
    val enum = exportCommander.fullExport(request.userId) |> exportFormatter.assignments
    val user = db.readOnlyMaster { implicit s => userRepo.get(request.userId) }
    val exportBase = s"${user.fullName.words.mkString("-")}-kifi-export"
    val exportFile = new File(exportBase + ".zip")
    val init = {
      val zip = new ZipOutputStream(new FileOutputStream(exportFile))
      zip.putNextEntry(new ZipEntry(s"$exportBase/index.html"))
      zip.write(HackyExportAssets.index.getBytes("UTF-8"))
      zip.putNextEntry(new ZipEntry(s"$exportBase/export.js"))
      (Set.empty[String], zip)
    }
    enum.run(Iteratee.fold(init) {
      case ((existingEntities, zip), (entity, contents)) =>
        if (!existingEntities.contains(entity)) {
          zip.write {
            s"$entity = ${Json.prettyPrint(contents)}\n".getBytes("UTF-8")
          }
        }
        (existingEntities + entity, zip)
    }).andThen {
      case Failure(fail) =>
        slackLog.error(s"[${clock.now}] Failed while writing user ${request.userId}'s export: ${fail.getMessage}")
        db.readWrite { implicit s => exportRequestRepo.markAsFailed(request.id.get, fail.getMessage) }
      case Success((entries, zip)) =>
        zip.closeEntry()
        zip.close()
        slackLog.info(s"[${clock.now}] Done writing user ${request.userId}'s export to $exportFile (${entries.size} entries), uploading to S3")
        exportStore.store(exportFile).andThen {
          case Success(yay) =>
            slackLog.info(s"[${clock.now}] Uploaded $exportBase.zip, key = ${yay.getKey}")
            db.readWrite { implicit s => exportRequestRepo.markAsComplete(request.id.get, yay.getKey) }
          case Failure(aww) =>
            slackLog.error(s"[${clock.now}] Could not upload $exportBase.zip because ${aww.getMessage}")
            db.readWrite { implicit s => exportRequestRepo.markAsFailed(request.id.get, aww.getMessage) }
            airbrake.notify(aww)
        }
    }.map(_ => ())
  }
}

