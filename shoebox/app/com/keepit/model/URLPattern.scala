package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.common.crypto._
import org.joda.time.DateTime
import play.api._
import play.api.libs.json._
import java.security.MessageDigest
import org.apache.commons.codec.binary.Base64
import scala.collection.mutable
import com.keepit.common.net.{URI, URINormalizer}
import com.keepit.serializer.{URLHistorySerializer => URLHS}
import com.keepit.inject._
import com.google.inject.{Inject, ImplementedBy, Singleton}
import play.api.Play.current

case class URLPattern (
  id: Option[Id[URLPattern]] = None,
  pattern: String,
  example: Option[String],
  createdAt: DateTime = inject[DateTime],
  updatedAt: DateTime = inject[DateTime],
  state: State[URLPattern] = URLPatternStates.ACTIVE
) extends Model[URLPattern] {
  def withId(id: Id[URLPattern]): URLPattern = copy(id = Some(id))
  def withPattern(pattern: String): URLPattern = copy(pattern = pattern)
  def withExample(example: Option[String]): URLPattern = copy(example = example)
  def withUpdateTime(when: DateTime): URLPattern = copy(updatedAt = when)
  def withState(state: State[URLPattern]): URLPattern = copy(state = state)
  def isActive = this.state == URLPatternStates.ACTIVE
}

object URLPatternStates extends States[URLPattern]

@ImplementedBy(classOf[URLPatternRepoImpl])
trait URLPatternRepo extends Repo[URLPattern] {
  def getActivePatterns()(implicit session: RSession): Seq[String]
  def get(pattern: String, excludeState: Option[State[URLPattern]] = Some(URLPatternStates.INACTIVE))
    (implicit session: RSession): Option[URLPattern]
}

@Singleton
class URLPatternRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[URLPattern] with URLPatternRepo {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[URLPattern](db, "url_pattern") {
    def pattern = column[String]("pattern", O.NotNull)
    def example = column[String]("example", O.Nullable)
    def * = id.? ~ pattern ~ example.? ~ createdAt ~ updatedAt ~ state <> (URLPattern, URLPattern.unapply _)
  }

  val activePatternsCache = new java.util.concurrent.atomic.AtomicReference[Seq[String]](null)  // TODO: memcache?

  override def save(pattern: URLPattern)(implicit session: RWSession): URLPattern = {
    val newPattern = super.save(pattern)
    activePatternsCache.set(null)
    val ruleRepo = inject[SliderRuleRepo]
    ruleRepo.getByName("url").foreach(ruleRepo.save(_))  // update timestamp of corresponding rule(s)
    newPattern
  }

  def getActivePatterns()(implicit session: RSession): Seq[String] =
    Option(activePatternsCache.get()).getOrElse {
      val patterns = (for(p <- table if p.state === URLPatternStates.ACTIVE) yield p.pattern).list
      activePatternsCache.set(patterns)
      patterns
    }

  def get(pattern: String, excludeState: Option[State[URLPattern]] = Some(URLPatternStates.INACTIVE))
      (implicit session: RSession): Option[URLPattern] =
    (for(p <- table if p.pattern === pattern && p.state =!= excludeState.getOrElse(null)) yield p).firstOption
}
