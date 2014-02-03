package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.{SequenceNumber, State}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import scala.slick.util.CloseableIterator
import com.keepit.common.time.Clock
import com.keepit.search.Lang
import scala.Some

@ImplementedBy(classOf[PhraseRepoImpl])
trait PhraseRepo extends Repo[Phrase] with SeqNumberFunction[Phrase]{
  def get(phrase: String, lang: Lang, excludeState: Option[State[Phrase]] = Some(PhraseStates.INACTIVE))(implicit session: RSession): Option[Phrase]
  def insertAll(phrases: Seq[Phrase])(implicit session: RWSession): Option[Int]
  def allIterator(implicit session: RSession): CloseableIterator[Phrase]
  def getPhrasesChanged(seq: SequenceNumber, fetchSize: Int)(implicit session: RSession): Seq[Phrase]
}

@Singleton
class PhraseRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[Phrase] with PhraseRepo with SeqNumberDbFunction[Phrase]{
  import db.Driver.Implicit._
  import DBSession._
  import FortyTwoTypeMappers._

  private val sequence = db.getSequence("phrase_sequence")

  override val table = new RepoTable[Phrase](db, "phrase") with SeqNumberColumn[Phrase]{
    def phrase = column[String]("phrase", O.NotNull)
    def source = column[String]("source", O.NotNull)
    def lang = column[Lang]("lang", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ phrase ~ lang ~ source ~ state ~ seq <> (Phrase.apply _, Phrase.unapply _)
  }

  override def save(phrase: Phrase)(implicit session: RWSession): Phrase = super.save(phrase.copy(seq = sequence.incrementAndGet()))

  override def deleteCache(model: Phrase)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: Phrase)(implicit session: RSession): Unit = {}

  def get(phrase: String, lang: Lang, excludeState: Option[State[Phrase]] = Some(PhraseStates.INACTIVE))(implicit session: RSession): Option[Phrase] =
    (for (f <- table if f.phrase === phrase && f.lang === lang && f.state =!= excludeState.getOrElse(null)) yield f).firstOption

  def insertAll(phrases: Seq[Phrase])(implicit session: RWSession): Option[Int] =
    table.insertAll(phrases: _*)

  def allIterator(implicit session: RSession): CloseableIterator[Phrase] = {
    table.map(t => t).elements
  }

  def getPhrasesChanged(seq: SequenceNumber, fetchSize: Int)(implicit session: RSession): Seq[Phrase] = super.getBySequenceNumber(seq, fetchSize)
}
