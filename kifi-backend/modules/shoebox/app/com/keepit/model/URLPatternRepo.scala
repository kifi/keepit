package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.State
import com.keepit.common.time.Clock

@ImplementedBy(classOf[URLPatternRepoImpl])
trait URLPatternRepo extends Repo[URLPattern] {
  def getActivePatterns()(implicit session: RSession): Seq[String]
  def get(pattern: String, excludeState: Option[State[URLPattern]] = Some(URLPatternStates.INACTIVE))(implicit session: RSession): Option[URLPattern]
}

@Singleton
class URLPatternRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock, ruleRepo: SliderRuleRepo) extends DbRepo[URLPattern] with URLPatternRepo {
  import scala.slick.lifted.Query
  import db.Driver.simple._
  import DBSession._

  type RepoImpl = UrlPatternTable
  case class UrlPatternTable(tag: Tag) extends RepoTable[URLPattern](db, tag, "url_pattern") {
    def pattern = column[String]("pattern", O.NotNull)
    def example = column[String]("example", O.Nullable)
    def * = (id.?, pattern, example.?, createdAt, updatedAt, state) <> (URLPattern.tupled, URLPattern.unapply _)
  }

  def table(tag: Tag) = new UrlPatternTable(tag)

  val activePatternsCache = new java.util.concurrent.atomic.AtomicReference[Seq[String]](null) // TODO: memcache?

  override def save(pattern: URLPattern)(implicit session: RWSession): URLPattern = {
    val newPattern = super.save(pattern)
    activePatternsCache.set(null)
    ruleRepo.getByName("url").foreach(ruleRepo.save(_)) // update timestamp of corresponding rule(s)
    newPattern
  }

  override def deleteCache(model: URLPattern)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: URLPattern)(implicit session: RSession): Unit = {}

  def getActivePatterns()(implicit session: RSession): Seq[String] =
    Option(activePatternsCache.get()).getOrElse {
      val patterns = (for (p <- rows if p.state === URLPatternStates.ACTIVE) yield p.pattern).list
      activePatternsCache.set(patterns)
      patterns
    }

  def get(pattern: String, excludeState: Option[State[URLPattern]] = Some(URLPatternStates.INACTIVE))(implicit session: RSession): Option[URLPattern] =
    (for (p <- rows if p.pattern === pattern && p.state =!= excludeState.getOrElse(null)) yield p).firstOption
}
