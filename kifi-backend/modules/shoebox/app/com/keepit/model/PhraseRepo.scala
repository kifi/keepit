package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.slick._
import com.keepit.common.db.{DbSequenceAssigner, SequenceNumber, State}
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.{SequencingActor, SchedulingProperties, SequencingPlugin}
import scala.slick.util.CloseableIterator
import com.keepit.common.time.Clock
import com.keepit.search.Lang
import scala.Some

@ImplementedBy(classOf[PhraseRepoImpl])
trait PhraseRepo extends Repo[Phrase] with SeqNumberFunction[Phrase] {
  def get(phrase: String, lang: Lang, excludeState: Option[State[Phrase]] = Some(PhraseStates.INACTIVE))(implicit session: RSession): Option[Phrase]
  def insertAll(phrases: Seq[Phrase])(implicit session: RWSession): Option[Int]
  def allIterator(implicit session: RSession): CloseableIterator[Phrase]
  def getPhrasesChanged(seq: SequenceNumber[Phrase], fetchSize: Int)(implicit session: RSession): Seq[Phrase]
}

@Singleton
class PhraseRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[Phrase] with PhraseRepo with SeqNumberDbFunction[Phrase] {

  import db.Driver.simple._

  type RepoImpl = PhraseTable
  class PhraseTable(tag: Tag) extends RepoTable[Phrase](db, tag, "phrase") with SeqNumberColumn[Phrase] {
    def phrase = column[String]("phrase", O.NotNull)
    def source = column[String]("source", O.NotNull)
    def lang = column[Lang]("lang", O.NotNull)
    def * = (id.?, createdAt, updatedAt, phrase, lang, source, state, seq) <> ((Phrase.apply _).tupled, Phrase.unapply _)
  }

  def table(tag: Tag) = new PhraseTable(tag)
  initTable()

  override def save(phrase: Phrase)(implicit session: RWSession): Phrase = {
    super.save(phrase.copy(seq = deferredSeqNum()))
  }

  override def deleteCache(model: Phrase)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: Phrase)(implicit session: RSession): Unit = {}

  def get(phrase: String, lang: Lang, excludeState: Option[State[Phrase]] = Some(PhraseStates.INACTIVE))(implicit session: RSession): Option[Phrase] =
    (for (f <- rows if f.phrase === phrase && f.lang === lang && f.state =!= excludeState.getOrElse(null)) yield f).firstOption

  def insertAll(phrases: Seq[Phrase])(implicit session: RWSession): Option[Int] =
    rows.insertAll(phrases: _*)

  def allIterator(implicit session: RSession): CloseableIterator[Phrase] = {
    rows.map(t => t).iterator
  }

  def getPhrasesChanged(seq: SequenceNumber[Phrase], fetchSize: Int)(implicit session: RSession): Seq[Phrase] = super.getBySequenceNumber(seq, fetchSize)
}

trait PhraseSequencingPlugin extends SequencingPlugin
class PhraseSequencingPluginImpl @Inject() (
  override val actor: ActorInstance[PhraseSequencingActor],
  override val scheduling: SchedulingProperties) extends PhraseSequencingPlugin

@Singleton
class PhraseSequenceNumberAssigner @Inject() (db: Database, repo: PhraseRepo, airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[Phrase](db, repo, airbrake)

class PhraseSequencingActor @Inject() (
  assigner: PhraseSequenceNumberAssigner,
  airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)
