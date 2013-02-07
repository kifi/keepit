package com.keepit.model

import com.keepit.inject._
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.common.crypto._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import play.api.Play.current
import play.api.libs.json._
import ru.circumflex.orm._
import java.security.MessageDigest
import org.apache.commons.codec.binary.Base64
import scala.collection.mutable
import com.keepit.common.logging.Logging
import com.keepit.common.net.URINormalizer
import com.keepit.common.net.URI
import com.google.inject._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.model._
import com.keepit.common.db._

case class URISearchResults(uri: NormalizedURI, score: Float)

case class NormalizedURIStats(uri: NormalizedURI, bookmarks: Seq[Bookmark])

case class NormalizedURI  (
  id: Option[Id[NormalizedURI]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[NormalizedURI] = ExternalId(),
  title: Option[String] = None,
  domain: Option[String] = None,
  url: String,
  urlHash: String,
  state: State[NormalizedURI] = NormalizedURIStates.ACTIVE
) extends ModelWithExternalId[NormalizedURI] with Logging {
  def withId(id: Id[NormalizedURI]): NormalizedURI = copy(id = Some(id))
  def withUpdateTime(now: DateTime): NormalizedURI = copy(updatedAt = now)

  def withState(state: State[NormalizedURI]) = copy(state = state)
  def withTitle(title: String) = if(title.isEmpty()) this else copy(title = Some(title))

  def save(implicit conn: Connection): NormalizedURI = {
    log.info("saving new uri %s with hash %s".format(url, urlHash))
    val entity = NormalizedURIEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    val uri = entity.view
    ScrapeInfoCxRepo.ofUriId(uri.id.get).save
//    inject[ScrapeInfoRepo].getByUri(uri).getOrElse(inject[ScrapeInfoRepo].save(ScrapeInfo(uri = this.id.get)))
    uri
  }

  def loadUsingHash(implicit conn: Connection): Option[NormalizedURI] =
    (NormalizedURIEntity AS "b").map { b => SELECT (b.*) FROM b WHERE (b.urlHash EQ urlHash) unique}.map(_.view)
}

@ImplementedBy(classOf[NormalizedURIRepoImpl])
trait NormalizedURIRepo extends DbRepo[NormalizedURI]  {
  def allActive()(implicit session: RSession): Seq[NormalizedURI]
  def getByState(state: State[NormalizedURI], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI]
  def getByDomain(domain: String)(implicit session: RSession): Seq[NormalizedURI]
  def getByNormalizedUrl(url: String)(implicit session: RSession): Option[NormalizedURI]
}

@Singleton
class NormalizedURIRepoImpl @Inject() (val db: DataBaseComponent) extends DbRepo[NormalizedURI] with NormalizedURIRepo {
  import FortyTwoTypeMappers._
  import org.scalaquery.ql._
  import org.scalaquery.ql.ColumnOps._
  import org.scalaquery.ql.basic.BasicProfile
  import org.scalaquery.ql.extended.ExtendedTable
  import db.Driver.Implicit._
  import DBSession._

  override lazy val table = new RepoTable[NormalizedURI](db, "normalized_uri") {
    def externalId = column[ExternalId[NormalizedURI]]("external_id")
    def title = column[String]("title")
    def url = column[String]("url", O.NotNull)
    def urlHash = column[String]("url_hash", O.NotNull)
    def domain = column[String]("domain", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ title.? ~ domain.? ~ url ~ urlHash ~ state <> (NormalizedURI, NormalizedURI.unapply _)
  }

  def allActive()(implicit session: RSession): Seq[NormalizedURI] =
    (for(f <- table if f.state === NormalizedURIStates.ACTIVE) yield f).list

  override def save(uri: NormalizedURI)(implicit session: RWSession): NormalizedURI = {
    val saved = super.save(uri)
    val scrapeRepo = inject[ScrapeInfoRepo]
    scrapeRepo.getByUri(saved.id.get).getOrElse(scrapeRepo.save(ScrapeInfo(uriId = saved.id.get)))
    saved
  }

  def getByState(state: State[NormalizedURI], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI] = {
    val limited = {
      val q = (for (t <- table if t.state === state) yield t)
      limit match {
        case some if some > 0 => q.take(some)
        case _ => q
      }
    }
    limited.list
  }

  def getByDomain(domain: String)(implicit session: RSession) =
    (for (t <- table if t.state === NormalizedURIStates.ACTIVE && t.domain === domain) yield t).list

  def getByNormalizedUrl(url: String)(implicit session: RSession): Option[NormalizedURI] = {
    val hash = NormalizedURIFactory.hashUrl(NormalizedURIFactory.normalize(url))
    (for (t <- table if t.urlHash === hash) yield t).firstOption
  }
}


object NormalizedURIFactory {

  def apply(url: String): NormalizedURI =
    apply(title = None, url = url, state = NormalizedURIStates.ACTIVE)

  def apply(title: String, url: String): NormalizedURI =
    NormalizedURIFactory(title = Some(title), url = url, state = NormalizedURIStates.ACTIVE)

  def apply(title: String, url: String, state: State[NormalizedURI]): NormalizedURI =
    NormalizedURIFactory(title = Some(title), url = url, state = state)

  def apply(title: Option[String], url: String, state: State[NormalizedURI]): NormalizedURI = {
    val normalized = normalize(url)
    NormalizedURI(title = title, url = normalized, domain = Option(URI.parse(normalized).flatMap(_.host).toString), urlHash = hashUrl(normalized), state = state)
  }

  def normalize(url: String) = URINormalizer.normalize(url)

  def hashUrl(normalizedUrl: String): String = {
    val binaryHash = MessageDigest.getInstance("MD5").digest(normalizedUrl.getBytes("UTF-8"))
    new String(new Base64().encode(binaryHash), "UTF-8")
  }
}

//slicked out!
object NormalizedURICxRepo {

  def getByState(state: State[NormalizedURI], limit: Int = -1)(implicit conn: Connection): Seq[NormalizedURI] = {
    if (limit <= 0) {
      (NormalizedURIEntity AS "n").map { n => SELECT (n.*) FROM n WHERE (n.state EQ state) }.list.map( _.view )
    } else {
      (NormalizedURIEntity AS "n").map { n => SELECT (n.*) FROM n WHERE (n.state EQ state) LIMIT limit }.list.map( _.view )
    }
  }

  def getByDomain(domain: String)(implicit conn: Connection) =
    (NormalizedURIEntity AS "n").map { n => SELECT (n.*) FROM n WHERE (n.domain EQ domain) }.list.map( _.view )

  def getByNormalizedUrl(url: String)(implicit conn: Connection): Option[NormalizedURI] = {
    val hash = NormalizedURIFactory.hashUrl(NormalizedURIFactory.normalize(url))
    (NormalizedURIEntity AS "b").map { b => SELECT (b.*) FROM b WHERE (b.urlHash EQ hash) unique }.map( _.view )
  }

  def all(implicit conn: Connection): Seq[NormalizedURI] =
    NormalizedURIEntity.all.map(_.view)

  def get(id: Id[NormalizedURI])(implicit conn: Connection): NormalizedURI =
    getOpt(id).getOrElse(throw NotFoundException(id))

  def getOpt(id: Id[NormalizedURI])(implicit conn: Connection): Option[NormalizedURI] =
    NormalizedURIEntity.get(id).map(_.view)

  def get(externalId: ExternalId[NormalizedURI])(implicit conn: Connection): NormalizedURI =
    getOpt(externalId).getOrElse(throw NotFoundException(externalId))

  def getOpt(externalId: ExternalId[NormalizedURI])(implicit conn: Connection): Option[NormalizedURI] =
    (NormalizedURIEntity AS "b").map { b => SELECT (b.*) FROM b WHERE (b.externalId EQ externalId) unique }.map(_.view)
}

object NormalizedURIStates {
  val ACTIVE = State[NormalizedURI]("active")
  val SCRAPED	= State[NormalizedURI]("scraped")
  val SCRAPE_FAILED = State[NormalizedURI]("scrape_failed")
  val UNSCRAPABLE = State[NormalizedURI]("unscrapable")
  val INDEXED = State[NormalizedURI]("indexed")
  val INDEX_FAILED = State[NormalizedURI]("index_failed")
  val FALLBACKED = State[NormalizedURI]("fallbacked")
  val FALLBACK_FAILED = State[NormalizedURI]("fallback_failed")
  val UNSCRAPE_FALLBACK = State[NormalizedURI]("unscrape_fallback")
  val UNSCRAPE_FALLBACK_FAILED = State[NormalizedURI]("unscrape_fallback_failed")
  val INACTIVE = State[NormalizedURI]("inactive")

  type Transitions = Map[State[NormalizedURI], Set[State[NormalizedURI]]]

  val ALL_TRANSITIONS: Transitions = Map(
      (ACTIVE -> Set(SCRAPED, SCRAPE_FAILED, UNSCRAPABLE, INACTIVE)),
      (SCRAPED -> Set(ACTIVE, INDEXED, INDEX_FAILED, INACTIVE)),
      (SCRAPE_FAILED -> Set(ACTIVE, FALLBACKED, FALLBACK_FAILED, INACTIVE)),
      (UNSCRAPABLE -> Set(ACTIVE, UNSCRAPE_FALLBACK, UNSCRAPE_FALLBACK_FAILED, INACTIVE)),
      (INDEXED -> Set(ACTIVE, SCRAPED, INACTIVE)),
      (INDEX_FAILED -> Set(ACTIVE, SCRAPED, INACTIVE)),
      (FALLBACKED -> Set(ACTIVE, SCRAPE_FAILED, INACTIVE)),
      (FALLBACK_FAILED -> Set(ACTIVE, SCRAPE_FAILED, INACTIVE)),
      (UNSCRAPE_FALLBACK -> Set(ACTIVE, UNSCRAPABLE, INACTIVE)),
      (UNSCRAPE_FALLBACK_FAILED -> Set(ACTIVE, UNSCRAPABLE, INACTIVE)),
      (INACTIVE -> Set(ACTIVE)))

  val ADMIN_TRANSITIONS: Transitions = Map(
      (ACTIVE -> Set.empty),
      (SCRAPED -> Set(ACTIVE)),
      (SCRAPE_FAILED -> Set(ACTIVE)),
      (UNSCRAPABLE -> Set(ACTIVE)),
      (INDEXED -> Set(ACTIVE, SCRAPED)),
      (INDEX_FAILED -> Set(ACTIVE, SCRAPED)),
      (FALLBACKED -> Set(ACTIVE, SCRAPE_FAILED)),
      (FALLBACK_FAILED -> Set(ACTIVE, SCRAPE_FAILED)),
      (UNSCRAPE_FALLBACK -> Set(ACTIVE, UNSCRAPABLE)),
      (UNSCRAPE_FALLBACK_FAILED -> Set(ACTIVE, UNSCRAPABLE)),
      (INACTIVE -> Set.empty))

  def transitionByAdmin[T](transition: (State[NormalizedURI], Set[State[NormalizedURI]]))(f:State[NormalizedURI]=>T) = {
    f(validate(transition, ADMIN_TRANSITIONS))
  }

  def findNextState(transition: (State[NormalizedURI], Set[State[NormalizedURI]])) = validate(transition, ALL_TRANSITIONS)

  private def validate(transition: (State[NormalizedURI], Set[State[NormalizedURI]]), transitions: Transitions): State[NormalizedURI] = {
    transition match {
      case (from, to) =>
        transitions.get(from) match {
          case Some(possibleStates) =>
            (possibleStates intersect to).headOption.getOrElse(throw new StateException("invalid transition: %s -> %s".format(from, to)))
          case None => throw new StateException("no such state: %s".format(from))
        }
    }
  }
}

private[model] class NormalizedURIEntity extends Entity[NormalizedURI, NormalizedURIEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val externalId = "external_id".EXTERNAL_ID[NormalizedURI].NOT_NULL(ExternalId())
  val title = "title".VARCHAR(2048)
  val url = "url".VARCHAR(2048).NOT_NULL
  val state = "state".STATE[NormalizedURI].NOT_NULL(NormalizedURIStates.ACTIVE)
  val urlHash = "url_hash".VARCHAR(512).NOT_NULL
  val domain = "domain".VARCHAR(512)

  def relation = NormalizedURIEntity

  def view(implicit conn: Connection): NormalizedURI = NormalizedURI(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    externalId = externalId(),
    title = title.value,
    url = url(),
    state = state(),
    urlHash = urlHash()
  )
}

private[model] object NormalizedURIEntity extends NormalizedURIEntity with EntityTable[NormalizedURI, NormalizedURIEntity] {
  override def relationName = "normalized_uri"

  def apply(view: NormalizedURI): NormalizedURIEntity = {
    val uri = new NormalizedURIEntity
    uri.id.set(view.id)
    uri.createdAt := view.createdAt
    uri.updatedAt := view.updatedAt
    uri.externalId := view.externalId
    uri.title.set(view.title)
    uri.url := view.url
    uri.state := view.state
    uri.urlHash := view.urlHash
    uri.domain.set(view.domain.map(_.toString))
    uri
  }
}


