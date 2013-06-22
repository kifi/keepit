package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.State
import com.keepit.common.time.Clock
import scala.Some

@ImplementedBy(classOf[URLPatternRepoImpl])
trait URLPatternRepo extends Repo[URLPattern] {
  def getActivePatterns()(implicit session: RSession): Seq[String]
  def get(pattern: String, excludeState: Option[State[URLPattern]] = Some(URLPatternStates.INACTIVE))
         (implicit session: RSession): Option[URLPattern]
}

@Singleton
class URLPatternRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock, ruleRepo: SliderRuleRepo) extends DbRepo[URLPattern] with URLPatternRepo {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[URLPattern](db, "url_pattern") {
    def pattern = column[String]("pattern", O.NotNull)
    def example = column[String]("example", O.Nullable)
    def * = id.? ~ pattern ~ example.? ~ createdAt ~ updatedAt ~ state <> (URLPattern, URLPattern.unapply _)
  }

  val activePatternsCache = new java.util.concurrent.atomic.AtomicReference[Seq[String]](null)  // TODO: memcache?

  override def save(pattern: URLPattern)(implicit session: RWSession): URLPattern = {
    val newPattern = super.save(pattern)
    activePatternsCache.set(null)
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
