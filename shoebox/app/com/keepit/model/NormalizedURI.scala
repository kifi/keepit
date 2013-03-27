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
import java.security.MessageDigest
import org.apache.commons.codec.binary.Base64
import scala.collection.mutable
import com.keepit.common.logging.Logging
import com.keepit.common.net.URINormalizer
import com.keepit.common.net.URI
import com.keepit.common.cache._
import com.keepit.serializer.NormalizedURISerializer
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.model._
import com.keepit.common.db._
import com.google.inject.Provider

import scala.concurrent.duration._
import com.google.inject.{Inject, ImplementedBy, Singleton}

case class URISearchResults(uri: NormalizedURI, score: Float)

case class NormalizedURI  (
  id: Option[Id[NormalizedURI]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[NormalizedURI] = ExternalId(),
  title: Option[String] = None,
  url: String,
  urlHash: String,
  state: State[NormalizedURI] = NormalizedURIStates.ACTIVE,
  seq: SequenceNumber = SequenceNumber.ZERO
) extends ModelWithExternalId[NormalizedURI] with Logging {
  def withId(id: Id[NormalizedURI]): NormalizedURI = copy(id = Some(id))
  def withUpdateTime(now: DateTime): NormalizedURI = copy(updatedAt = now)

  def withState(state: State[NormalizedURI]) = copy(state = state)
  def withTitle(title: String) = if (title.isEmpty()) this else copy(title = Some(title))
}

@ImplementedBy(classOf[NormalizedURIRepoImpl])
trait NormalizedURIRepo extends DbRepo[NormalizedURI] with ExternalIdColumnDbFunction[NormalizedURI] {
  def allActive()(implicit session: RSession): Seq[NormalizedURI]
  def getByState(state: State[NormalizedURI], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI]
  def getByNormalizedUrl(url: String)(implicit session: RSession): Option[NormalizedURI]
  def getIndexable(sequenceNumber: SequenceNumber, limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI]
}

case class NormalizedURIKey(id: Id[NormalizedURI]) extends Key[NormalizedURI] {
  val namespace = "uri_by_id"
  def toKey(): String = id.id.toString
}
class NormalizedURICache @Inject() (val repo: FortyTwoCachePlugin) extends FortyTwoCache[NormalizedURIKey, NormalizedURI] {
  val ttl = 7 days
  def deserialize(obj: Any): NormalizedURI = NormalizedURISerializer.normalizedURISerializer.reads(Json.parse(obj.asInstanceOf[String]).asInstanceOf[JsObject]).get
  def serialize(uri: NormalizedURI) = NormalizedURISerializer.normalizedURISerializer.writes(uri)
}

@Singleton
class NormalizedURIRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  idCache: NormalizedURICache,
  scrapeRepoProvider: Provider[ScrapeInfoRepo])
    extends DbRepo[NormalizedURI] with NormalizedURIRepo with ExternalIdColumnDbFunction[NormalizedURI] {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  private val sequence = db.getSequence("normalized_uri_sequence")

  override lazy val table = new RepoTable[NormalizedURI](db, "normalized_uri") with ExternalIdColumn[NormalizedURI] {
    def title = column[String]("title")
    def url = column[String]("url", O.NotNull)
    def urlHash = column[String]("url_hash", O.NotNull)
    def seq = column[SequenceNumber]("seq", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ title.? ~ url ~ urlHash ~ state ~ seq <> (NormalizedURI,
        NormalizedURI.unapply _)
  }

  def getIndexable(sequenceNumber: SequenceNumber, limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI] = {
    val q = (for (f <- table if f.seq > sequenceNumber) yield f).sortBy(_.seq)
    (if (limit >= 0) q.take(limit) else q).list
  }

  override def invalidateCache(uri: NormalizedURI)(implicit session: RSession) = {
    uri.id match {
      case Some(id) => idCache.set(NormalizedURIKey(id), uri)
      case None =>
    }
    uri
  }

  override def get(id: Id[NormalizedURI])(implicit session: RSession): NormalizedURI = {
    idCache.getOrElse(NormalizedURIKey(id)) {
      (for(f <- table if f.id is id) yield f).first
    }
  }

  def allActive()(implicit session: RSession): Seq[NormalizedURI] =
    (for(f <- table if f.state === NormalizedURIStates.ACTIVE) yield f).list

  override def save(uri: NormalizedURI)(implicit session: RWSession): NormalizedURI = {
    val num = sequence.incrementAndGet()
    val saved = super.save(uri.copy(seq = num))

    lazy val scrapeRepo = scrapeRepoProvider.get
    if (uri.state == NormalizedURIStates.INACTIVE || uri.state == NormalizedURIStates.ACTIVE) {
      // If uri.state is ACTIVE or INACTIVE, we do not want an ACTIVE ScrapeInfo record for it
      scrapeRepo.getByUri(saved.id.get) match {
        case Some(scrapeInfo) if scrapeInfo.state == ScrapeInfoStates.ACTIVE =>
          scrapeRepo.save(scrapeInfo.withState(ScrapeInfoStates.INACTIVE))
        case _ => // do nothing
      }
    } else {
      // Otherwise, ensure that ScrapeInfo has an active record for it.
      scrapeRepo.getByUri(saved.id.get) match {
        case Some(scrapeInfo) if scrapeInfo.state == ScrapeInfoStates.INACTIVE =>
          scrapeRepo.save(scrapeInfo.withState(ScrapeInfoStates.ACTIVE))
        case Some(scrapeInfo) => // do nothing
        case None =>
          scrapeRepo.save(ScrapeInfo(uriId = saved.id.get))
      }
    }

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

  def getByNormalizedUrl(url: String)(implicit session: RSession): Option[NormalizedURI] = {
    val hash = NormalizedURIFactory.hashUrl(NormalizedURIFactory.normalize(url))
    (for (t <- table if t.urlHash === hash) yield t).firstOption
  }
}


object NormalizedURIFactory {

  def apply(url: String): NormalizedURI =
    apply(title = None, url = url, state = NormalizedURIStates.ACTIVE)

  def apply(url: String, state: State[NormalizedURI]): NormalizedURI =
    apply(title = None, url = url, state = state)

  def apply(title: String, url: String): NormalizedURI =
    NormalizedURIFactory(title = Some(title), url = url, state = NormalizedURIStates.ACTIVE)

  def apply(title: String, url: String, state: State[NormalizedURI]): NormalizedURI =
    NormalizedURIFactory(title = Some(title), url = url, state = state)

  def apply(title: Option[String], url: String, state: State[NormalizedURI]): NormalizedURI = {
    val normalized = normalize(url)
    NormalizedURI(title = title, url = normalized, urlHash = hashUrl(normalized), state = state)
  }

  def normalize(url: String) = URINormalizer.normalize(url)

  def hashUrl(normalizedUrl: String): String = {
    val binaryHash = MessageDigest.getInstance("MD5").digest(normalizedUrl.getBytes("UTF-8"))
    new String(new Base64().encode(binaryHash), "UTF-8")
  }
}

object NormalizedURIStates extends States[NormalizedURI] {
  val SCRAPED	= State[NormalizedURI]("scraped")
  val SCRAPE_FAILED = State[NormalizedURI]("scrape_failed")
  val UNSCRAPABLE = State[NormalizedURI]("unscrapable")
  val SCRAPE_WANTED = State[NormalizedURI]("scrape_wanted")

  type Transitions = Map[State[NormalizedURI], Set[State[NormalizedURI]]]

  val ALL_TRANSITIONS: Transitions = Map(
      (ACTIVE -> Set(SCRAPE_WANTED)),
      (SCRAPE_WANTED -> Set(SCRAPED, SCRAPE_FAILED, UNSCRAPABLE, INACTIVE)),
      (SCRAPED -> Set(SCRAPE_WANTED, INACTIVE)),
      (SCRAPE_FAILED -> Set(SCRAPE_WANTED, INACTIVE)),
      (UNSCRAPABLE -> Set(SCRAPE_WANTED, INACTIVE)),
      (INACTIVE -> Set(SCRAPE_WANTED, ACTIVE, INACTIVE)))

  val ADMIN_TRANSITIONS: Transitions = Map(
      (ACTIVE -> Set.empty),
      (SCRAPED -> Set(ACTIVE)),
      (SCRAPE_FAILED -> Set(ACTIVE)),
      (UNSCRAPABLE -> Set(ACTIVE)),
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
