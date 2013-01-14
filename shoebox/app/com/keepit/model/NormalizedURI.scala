package com.keepit.model

import com.keepit.common.db.{CX, Id, Entity, EntityTable, ExternalId, State}
import com.keepit.common.db.NotFoundException
import com.keepit.common.db.StateException
import com.keepit.common.time._
import com.keepit.common.crypto._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import play.api.libs.json._
import ru.circumflex.orm._
import java.security.MessageDigest
import org.apache.commons.codec.binary.Base64
import scala.collection.mutable
import com.keepit.common.logging.Logging
import com.keepit.common.net.URINormalizer
import com.keepit.common.net.URI

case class URISearchResults(uri: NormalizedURI, score: Float)

case class NormalizedURIStats(uri: NormalizedURI, bookmarks: Seq[Bookmark])

case class NormalizedURI  (
  id: Option[Id[NormalizedURI]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[NormalizedURI] = ExternalId(),
  title: Option[String] = None,
  url: String,
  urlHash: String,
  state: State[NormalizedURI] = NormalizedURI.States.ACTIVE
) extends Logging {

  def domain = URI.parse(url).flatMap(_.host)

  def save(implicit conn: Connection): NormalizedURI = {
    log.info("saving new uri %s with hash %s".format(url, urlHash))
    val entity = NormalizedURIEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    val uri = entity.view
    ScrapeInfo.ofUri(uri).save
    uri
  }

  def withState(state: State[NormalizedURI]) = copy(state = state)

  def withTitle(title: String) = if(title.isEmpty()) this else copy(title = Some(title))

  def loadUsingHash(implicit conn: Connection): Option[NormalizedURI] =
    (NormalizedURIEntity AS "b").map { b => SELECT (b.*) FROM b WHERE (b.urlHash EQ urlHash) unique}.map(_.view)


  def stats()(implicit conn: Connection): NormalizedURIStats = NormalizedURIStats(this, BookmarkCxRepo.ofUri(this))
}

object NormalizedURI {

  def apply(url: String): NormalizedURI =
    apply(title = None, url = url, state = NormalizedURI.States.ACTIVE)

  def apply(title: String, url: String): NormalizedURI =
    NormalizedURI(title = Some(title), url = url, state = NormalizedURI.States.ACTIVE)

  def apply(title: String, url: String, state: State[NormalizedURI]): NormalizedURI =
    NormalizedURI(title = Some(title), url = url, state = state)

  def apply(title: Option[String], url: String, state: State[NormalizedURI]): NormalizedURI = {
    val normalized = normalize(url)
    NormalizedURI(title = title, url = normalized, urlHash = hashUrl(normalized), state = state)
  }

  private def normalize(url: String) = URINormalizer.normalize(url)

  private def hashUrl(normalizedUrl: String): String = {
    val binaryHash = MessageDigest.getInstance("MD5").digest(normalizedUrl.getBytes("UTF-8"))
    new String(new Base64().encode(binaryHash), "UTF-8")
  }

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
    val hash = hashUrl(normalize(url))
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

  object States {
    val ACTIVE = State[NormalizedURI]("active")
    val SCRAPED	= State[NormalizedURI]("scraped")
    val SCRAPE_FAILED = State[NormalizedURI]("scrape_failed")
    val INDEXED = State[NormalizedURI]("indexed")
    val INDEX_FAILED = State[NormalizedURI]("index_failed")
    val FALLBACKED = State[NormalizedURI]("fallbacked")
    val FALLBACK_FAILED = State[NormalizedURI]("fallback_failed")
    val INACTIVE = State[NormalizedURI]("inactive")

    type Transitions = Map[State[NormalizedURI], Set[State[NormalizedURI]]]

    val ALL_TRANSITIONS: Transitions = Map(
        (ACTIVE -> Set(SCRAPED, SCRAPE_FAILED, INACTIVE)),
        (SCRAPED -> Set(ACTIVE, INDEXED, INDEX_FAILED, INACTIVE)),
        (SCRAPE_FAILED -> Set(ACTIVE, FALLBACKED, FALLBACK_FAILED, INACTIVE)),
        (INDEXED -> Set(ACTIVE, SCRAPED, INACTIVE)),
        (INDEX_FAILED -> Set(ACTIVE, SCRAPED, INACTIVE)),
        (FALLBACKED -> Set(ACTIVE, SCRAPE_FAILED, INACTIVE)),
        (FALLBACK_FAILED -> Set(ACTIVE, SCRAPE_FAILED, INACTIVE)),
        (INACTIVE -> Set(ACTIVE)))

    val ADMIN_TRANSITIONS: Transitions = Map(
        (ACTIVE -> Set.empty),
        (SCRAPED -> Set(ACTIVE)),
        (SCRAPE_FAILED -> Set(ACTIVE)),
        (INDEXED -> Set(ACTIVE, SCRAPED)),
        (INDEX_FAILED -> Set(ACTIVE, SCRAPED)),
        (FALLBACKED -> Set(ACTIVE, SCRAPE_FAILED)),
        (FALLBACK_FAILED -> Set(ACTIVE, SCRAPE_FAILED)),
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
}

private[model] class NormalizedURIEntity extends Entity[NormalizedURI, NormalizedURIEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val externalId = "external_id".EXTERNAL_ID[NormalizedURI].NOT_NULL(ExternalId())
  val title = "title".VARCHAR(2048)
  val url = "url".VARCHAR(256).NOT_NULL
  val state = "state".STATE[NormalizedURI].NOT_NULL(NormalizedURI.States.ACTIVE)
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


