package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulingProperties, SchedulerPlugin }
import com.keepit.common.queue.messages.{ SuggestedSearchTermsWithLibraryId, LibrarySuggestedSearchRequest }
import com.keepit.model._
import com.kifi.franz.SQSQueue
import scala.concurrent.Future
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.util.{ Failure, Success }

@ImplementedBy(classOf[SuggestedSearchTermUpdatePluginImpl])
trait SuggestedSearchTermUpdatePlugin

@Singleton
class SuggestedSearchTermUpdatePluginImpl @Inject() (
    actor: ActorInstance[SuggestedSearchTermUpdateActor],
    val scheduling: SchedulingProperties) extends SchedulerPlugin with SuggestedSearchTermUpdatePlugin {
  import SuggestedSearchTermUpdateActor.SuggestedSearchTermUpdateActorMessages._

  override def enabled: Boolean = true

  val name: String = getClass.toString

  override def onStart() { //kill
    //    scheduleTaskOnOneMachine(actor.system, 2 minutes, 5 minutes, actor.ref, Update, this.getClass.getSimpleName + Update.getClass.toString)
    //    scheduleTaskOnOneMachine(actor.system, 5 minutes, 1 minutes, actor.ref, CollectResult, this.getClass.getSimpleName + CollectResult.getClass.toString)
  }

}

class SuggestedSearchTermUpdateActor @Inject() (
    updater: SuggestedSearchTermUpdater,
    airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) {

  import SuggestedSearchTermUpdateActor.SuggestedSearchTermUpdateActorMessages._

  val collectLock = new ReactiveLock(1)

  def receive = {
    case Update =>
      val cnt = updater.update()
      if (cnt == updater.KEEPS_BATCH_SIZE) context.system.scheduler.scheduleOnce(10 seconds, self, Update)
    case CollectResult =>
      collectLock.withLockFuture {
        updater.collectResult().map { cnt => if (cnt > 0) context.system.scheduler.scheduleOnce(2 seconds, self, CollectResult) }
      }
  }
}

object SuggestedSearchTermUpdateActor {
  sealed trait SuggestedSearchTermUpdateActorMessage
  object SuggestedSearchTermUpdateActorMessages {
    case object Update extends SuggestedSearchTermUpdateActorMessage
    case object CollectResult extends SuggestedSearchTermUpdateActorMessage
  }
}

class SuggestedSearchTermUpdater @Inject() (
    db: Database,
    requestQueue: SQSQueue[LibrarySuggestedSearchRequest],
    resultQueue: SQSQueue[SuggestedSearchTermsWithLibraryId],
    suggestedSearchCmdr: LibrarySuggestedSearchCommander,
    sysValueRepo: SystemValueRepo,
    keepRepo: KeepRepo) extends Logging {

  private val SYS_SEQ_NAME: Name[SequenceNumber[Keep]] = Name("suggested_search_snyc_keep")
  val KEEPS_BATCH_SIZE = 500

  def collectResult(): Future[Int] = {
    resultQueue.next.map { msgOpt =>
      msgOpt match {
        case Some(msg) =>
          val SuggestedSearchTermsWithLibraryId(libId, terms) = msg.body
          log.info(s"collect one result from resultQueue, libraryId = ${libId}, terms = ${terms.takeTopK(5).terms.mkString(", ")} ...")
          suggestedSearchCmdr.saveSuggestedSearchTermsForLibrary(libId, terms, SuggestedSearchTermKind.AUTO)
          msg.consume()
          1
        case None => 0
      }
    }
  }

  def update(): Int = {

    val keeps = getKeepsToProcess()
    if (keeps.nonEmpty) {
      val highestSeq = keeps.map { _.seq }.max
      log.info(s"${keeps.size} keeps retrieved")

      val libs = librariesNeedUpdate(keeps)
      log.info(s"${libs.size} libraries need tag computations, ${libs.take(10).mkString(", ")}")

      libs.foreach { lib => computeAndSaveHashtags(lib) }
      libs.foreach { lib => sendAutotagRequests(lib) }

      log.info(s"suggested search keep seqNum sync: set seqNum to ${highestSeq}")
      db.readWrite { implicit s => sysValueRepo.setSequenceNumber(SYS_SEQ_NAME, highestSeq) }
    }
    keeps.size
  }

  private def getKeepsToProcess(): Seq[Keep] = {
    db.readOnlyReplica { implicit s =>
      val lowSeq = sysValueRepo.getSequenceNumber(SYS_SEQ_NAME) getOrElse SequenceNumber[Keep](0)
      log.info(s"suggested search keep seqNum sync: from ${lowSeq}")
      keepRepo.getBookmarksChanged(lowSeq, KEEPS_BATCH_SIZE)
    }
  }

  private def librariesNeedUpdate(keeps: Seq[Keep]): Seq[Id[Library]] = {

    val groupedKeeps = keeps.filter(_.lowestLibraryId.isDefined).groupBy(_.lowestLibraryId.get)
    val libMaxKeepMap: Map[Id[Library], SequenceNumber[Keep]] = db.readOnlyReplica { implicit s => keepRepo.getMaxKeepSeqNumForLibraries(groupedKeeps.keySet) }

    groupedKeeps
      .map { case (libId, libKeeps) => (libId, libKeeps.map { _.seq }.max) }
      .flatMap {
        case (libId, localMaxSeq) =>
          // if the library contains a keep which has higher seqNum, don't do it now, will do it at a future point.
          if (localMaxSeq < libMaxKeepMap.getOrElse(libId, SequenceNumber[Keep](0))) None else Some(libId)
      }.toSeq
  }

  private def computeAndSaveHashtags(id: Id[Library]): Unit = {
    val hashtags = suggestedSearchCmdr.aggregateHashtagsForLibrary(id, maxKeeps = 5000, maxTerms = 50)
    suggestedSearchCmdr.saveSuggestedSearchTermsForLibrary(id, hashtags, SuggestedSearchTermKind.HASHTAG)
  }

  private def sendAutotagRequests(id: Id[Library]): Unit = {
    requestQueue.send(LibrarySuggestedSearchRequest(id))
  }
}
