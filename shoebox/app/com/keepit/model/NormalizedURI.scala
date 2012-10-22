package com.keepit.model

import com.keepit.common.db.{ CX, Id, Entity, EntityTable, ExternalId, State }
import com.keepit.common.db.NotFoundException
import com.keepit.common.db.StateException
import com.keepit.common.time._
import com.keepit.common.crypto._
import java.security.SecureRandom
import java.sql.Connection
import org.joda.time.DateTime
import play.api._
import ru.circumflex.orm._
import java.net.URI
import java.security.MessageDigest
import org.apache.commons.codec.binary.Base64
import scala.collection.mutable
import com.keepit.common.logging.Logging

case class URISearchResults(uri: NormalizedURI, score: Float)

case class NormalizedURIStats(uri: NormalizedURI, bookmarks: Seq[Bookmark])

case class NormalizedURI(
    id: Option[Id[NormalizedURI]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    externalId: ExternalId[NormalizedURI] = ExternalId(),
    title: String,
    url: String,
    urlHash: String,
    state: State[NormalizedURI] = NormalizedURI.States.ACTIVE) extends Logging {

  def save(implicit conn: Connection): NormalizedURI = {
    log.info("saving new uri %s with hash %s".format(url, urlHash))
    val entity = NormalizedURIEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }

  def withState(state: State[NormalizedURI]) = copy(state = state)

  def loadUsingHash(implicit conn: Connection): Option[NormalizedURI] =
    (NormalizedURIEntity AS "b").map { b ⇒ SELECT(b.*) FROM b WHERE (b.urlHash EQ urlHash) unique }.map(_.view)

  private var bookmarksCache: Option[Seq[Bookmark]] = None

  def bookmarks()(implicit conn: Connection): Seq[Bookmark] = bookmarksCache match {
    case None ⇒
      val res = Bookmark.ofUri(this)
      bookmarksCache = Some(res)
      res
    case Some(bmks) ⇒
      bmks
  }

  def stats()(implicit conn: Connection): NormalizedURIStats = {
    var uriBookmarks = bookmarks()
    NormalizedURIStats(this, uriBookmarks)
  }
}

object NormalizedURI {

  def apply(title: String, url: String): NormalizedURI = {
    //better: use http://stackoverflow.com/a/4057470/81698
    val normalized = normalize(url)
    NormalizedURI(title = title, url = normalized, urlHash = hashUrl(normalized))
  }

  def apply(title: String, url: String, state: State[NormalizedURI]): NormalizedURI = {
    //better: use http://stackoverflow.com/a/4057470/81698
    val normalized = normalize(url)
    NormalizedURI(title = title, url = normalized, urlHash = hashUrl(normalized), state = state)
  }

  private def normalize(url: String) = url //new URI(url).normalize().toString()

  private def hashUrl(normalizedUrl: String): String = {
    val binaryHash = MessageDigest.getInstance("MD5").digest(normalizedUrl.getBytes("UTF-8"))
    new String(new Base64().encode(binaryHash), "UTF-8")
  }

  def getByState(state: State[NormalizedURI], limit: Int = -1)(implicit conn: Connection): Seq[NormalizedURI] = {
    if (limit <= 0) {
      (NormalizedURIEntity AS "n").map { n ⇒ SELECT(n.*) FROM n WHERE (n.state EQ state) }.list.map(_.view)
    }
    else {
      (NormalizedURIEntity AS "n").map { n ⇒ SELECT(n.*) FROM n WHERE (n.state EQ state) LIMIT limit }.list.map(_.view)
    }
  }

  def getByNormalizedUrl(url: String)(implicit conn: Connection): Option[NormalizedURI] = {
    var hash = hashUrl(normalize(url))
    (NormalizedURIEntity AS "b").map { b ⇒ SELECT(b.*) FROM b WHERE (b.urlHash EQ hash) unique }.map(_.view)
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
    (NormalizedURIEntity AS "b").map { b ⇒ SELECT(b.*) FROM b WHERE (b.externalId EQ externalId) unique }.map(_.view)

  object States {
    val ACTIVE = State[NormalizedURI]("active")
    val SCRAPED = State[NormalizedURI]("scraped")
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

    def transitionByAdmin[T](transition: (State[NormalizedURI], Set[State[NormalizedURI]]))(f: State[NormalizedURI] ⇒ T) = {
      f(validate(transition, ADMIN_TRANSITIONS))
    }

    def findNextState(transition: (State[NormalizedURI], Set[State[NormalizedURI]])) = validate(transition, ALL_TRANSITIONS)

    private def validate(transition: (State[NormalizedURI], Set[State[NormalizedURI]]), transitions: Transitions): State[NormalizedURI] = {
      transition match {
        case (from, to) ⇒
          transitions.get(from) match {
            case Some(possibleStates) ⇒
              (possibleStates intersect to).headOption.getOrElse(throw new StateException("invalid transition: %s -> %s".format(from, to)))
            case None ⇒ throw new StateException("no such state: %s".format(from))
          }
      }
    }
  }
}

private[model] class NormalizedURIEntity extends Entity[NormalizedURI, NormalizedURIEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val externalId = "external_id".EXTERNAL_ID[NormalizedURI].NOT_NULL(ExternalId())
  val title = "title".VARCHAR(256).NOT_NULL
  val url = "url".VARCHAR(256).NOT_NULL
  val state = "state".STATE[NormalizedURI].NOT_NULL(NormalizedURI.States.ACTIVE)
  val urlHash = "url_hash".VARCHAR(512).NOT_NULL

  def relation = NormalizedURIEntity

  def view(implicit conn: Connection): NormalizedURI = NormalizedURI(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    externalId = externalId(),
    title = title(),
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
    uri.title := view.title
    uri.url := view.url
    uri.state := view.state
    uri.urlHash := view.urlHash
    uri
  }
}

