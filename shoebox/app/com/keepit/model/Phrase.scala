package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.search.Lang

case class Phrase (
  id: Option[Id[Phrase]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  phrase: String,
  lang: Lang,
  source: String,
  state: State[Phrase] = PhraseStates.ACTIVE
  ) extends Model[Phrase] {
  def withId(id: Id[Phrase]): Phrase = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

@ImplementedBy(classOf[PhraseRepoImpl])
trait PhraseRepo extends Repo[Phrase] {
  def get(phrase: String, lang: Lang, excludeState: Option[State[Phrase]])(implicit session: RSession): Option[Phrase]
}

@Singleton
class PhraseRepoImpl @Inject() (val db: DataBaseComponent) extends DbRepo[Phrase] with PhraseRepo {
  import db.Driver.Implicit._
  import DBSession._
  import FortyTwoTypeMappers._

  override lazy val table = new RepoTable[Phrase](db, "phrase") {
    def phrase = column[String]("phrase", O.NotNull)
    def source = column[String]("source", O.NotNull)
    def lang = column[Lang]("lang", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ phrase ~ lang ~ source ~ state <> (Phrase, Phrase.unapply _)
  }

  def get(phrase: String, lang: Lang, excludeState: Option[State[Phrase]] = Some(PhraseStates.INACTIVE))(implicit session: RSession): Option[Phrase] =
    (for (f <- table if f.phrase === phrase && f.lang === lang && f.state =!= excludeState.getOrElse(null)) yield f).firstOption

}

object PhraseStates extends States[Phrase]
