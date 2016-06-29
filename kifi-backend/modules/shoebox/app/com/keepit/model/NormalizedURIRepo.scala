package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.common.db.{ State, SequenceNumber, Id }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.logging.Logging
import org.joda.time.DateTime
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.strings._

class UriInternException(msg: String, cause: Throwable) extends Exception(msg, cause)

@ImplementedBy(classOf[NormalizedURIRepoImpl])
trait NormalizedURIRepo extends DbRepo[NormalizedURI] with ExternalIdColumnDbFunction[NormalizedURI] with SeqNumberFunction[NormalizedURI] {
  def getByState(state: State[NormalizedURI], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI]
  def getIndexable(sequenceNumber: SequenceNumber[NormalizedURI], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI]
  def getIndexablesWithContent(sequenceNumber: SequenceNumber[NormalizedURI], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI]
  def getChanged(sequenceNumber: SequenceNumber[NormalizedURI], includeStates: Set[State[NormalizedURI]], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI]
  def getCurrentSeqNum()(implicit session: RSession): SequenceNumber[NormalizedURI]
  def getByNormalizedUrl(normalizedUrl: String)(implicit session: RSession): Option[NormalizedURI]
  def getByNormalizedUrls(normalizedUrls: Seq[String])(implicit session: RSession): Map[String, NormalizedURI]
  def getByUrlHash(urlHash: UrlHash)(implicit session: RSession): Option[NormalizedURI]
  def getByRedirection(redirect: Id[NormalizedURI])(implicit session: RSession): Seq[NormalizedURI]
  def save(uri: NormalizedURI)(implicit session: RWSession): NormalizedURI
  def toBeRemigrated()(implicit session: RSession): Seq[NormalizedURI]
  def updateURIRestriction(id: Id[NormalizedURI], r: Option[Restriction])(implicit session: RWSession): Unit
  def getRestrictedURIs(targetRestriction: Restriction)(implicit session: RSession): Seq[NormalizedURI]
  def checkRecommendable(uriIds: Seq[Id[NormalizedURI]])(implicit session: RSession): Seq[Boolean]
  def getFromId(fromId: Id[NormalizedURI])(implicit session: RSession): Seq[NormalizedURI]
  def getByRegex(regex: String)(implicit session: RSession): Seq[NormalizedURI]
  def getByIds(uriIds: Set[Id[NormalizedURI]])(implicit session: RSession): Map[Id[NormalizedURI], NormalizedURI]
}

@Singleton
class NormalizedURIRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  idCache: NormalizedURICache,
  urlHashCache: NormalizedURIUrlHashCache,
  airbrake: AirbrakeNotifier)
    extends DbRepo[NormalizedURI] with NormalizedURIRepo with ExternalIdColumnDbFunction[NormalizedURI] with SeqNumberDbFunction[NormalizedURI] with Logging {

  import db.Driver.simple._

  type RepoImpl = NormalizedURITable
  class NormalizedURITable(tag: Tag) extends RepoTable[NormalizedURI](db, tag, "normalized_uri") with ExternalIdColumn[NormalizedURI] with SeqNumberColumn[NormalizedURI] {
    def title = column[Option[String]]("title", O.Nullable)
    def url = column[String]("url", O.NotNull)
    def urlHash = column[UrlHash]("url_hash", O.NotNull)
    def restriction = column[Option[Restriction]]("restriction", O.Nullable)
    def normalization = column[Option[Normalization]]("normalization", O.Nullable)
    def redirect = column[Option[Id[NormalizedURI]]]("redirect", O.Nullable)
    def redirectTime = column[Option[DateTime]]("redirect_time", O.Nullable)
    def shouldHaveContent = column[Boolean]("should_have_content", O.NotNull)
    def * = (id.?, createdAt, updatedAt, externalId, title, url, urlHash, state, seq, restriction, normalization, redirect, redirectTime, shouldHaveContent) <> ((NormalizedURI.apply _).tupled, NormalizedURI.unapply _)
  }

  def table(tag: Tag) = new NormalizedURITable(tag)
  initTable()

  def getIndexable(sequenceNumber: SequenceNumber[NormalizedURI], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI] = {
    super.getBySequenceNumber(sequenceNumber, limit)
  }

  def getIndexablesWithContent(sequenceNumber: SequenceNumber[NormalizedURI], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI] = {
    val excludeStates = Set(NormalizedURIStates.INACTIVE, NormalizedURIStates.REDIRECTED) // todo(Léo): Just include ACTIVE once other states are gone
    val q = (for (f <- rows if f.seq > sequenceNumber && !f.state.inSet(excludeStates) && f.shouldHaveContent) yield f)
    q.sortBy(_.seq).take(limit).list
  }

  def getChanged(sequenceNumber: SequenceNumber[NormalizedURI], states: Set[State[NormalizedURI]], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI] = {
    val q = (for (f <- rows if f.seq > sequenceNumber && f.state.inSet(states)) yield f).sortBy(_.seq)
    (if (limit >= 0) q.take(limit) else q).list
  }

  override def getCurrentSeqNum()(implicit session: RSession): SequenceNumber[NormalizedURI] = {
    sequence.getLastGeneratedSeq()
  }

  override def invalidateCache(uri: NormalizedURI)(implicit session: RSession): Unit = {
    if (uri.state == NormalizedURIStates.INACTIVE) {
      deleteCache(uri)
    } else {
      uri.id map { id => idCache.set(NormalizedURIKey(id), uri) }
      urlHashCache.set(NormalizedURIUrlHashKey(UrlHash.hashUrl(uri.url)), uri)
    }
  }

  override def deleteCache(uri: NormalizedURI)(implicit session: RSession): Unit = {
    uri.id map { id => idCache.remove(NormalizedURIKey(id)) }
    urlHashCache.remove(NormalizedURIUrlHashKey(UrlHash.hashUrl(uri.url)))
  }

  override def get(id: Id[NormalizedURI])(implicit session: RSession): NormalizedURI = {
    idCache.getOrElse(NormalizedURIKey(id)) { getCompiled(id).first }
  }

  override def save(uri: NormalizedURI)(implicit session: RWSession): NormalizedURI = {

    // setting a negative sequence number for deferred assignment
    val num = deferredSeqNum()
    val uriWithSeq = uri.copy(seq = num)

    val validatedUri = if (uri.state != NormalizedURIStates.REDIRECTED && (uri.redirect.isDefined || uri.redirectTime.isDefined)) {
      airbrake.notify(s"uri ${uri.id} had redirection info. We are trying to save it with state ${uri.state}. Going to save state as redirected.")
      uriWithSeq.copy(state = NormalizedURIStates.REDIRECTED)
    } else uriWithSeq

    val cleaned = {
      val cleanTitle = validatedUri.title.map(_.trimAndRemoveLineBreaks.abbreviate(NormalizedURI.TitleMaxLen))
      validatedUri.copy(title = cleanTitle)
    }
    val saved = try {
      super.save(cleaned)
    } catch {
      case e: Throwable =>
        deleteCache(cleaned)
        throw e
    }

    saved
  }

  def getByState(state: State[NormalizedURI], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI] = {
    val limited = {
      val q = (for (t <- rows if t.state === state) yield t)
      limit match {
        case some if some > 0 => q.take(some)
        case _ => q
      }
    }
    limited.list
  }

  def getByNormalizedUrl(normalizedUrl: String)(implicit session: RSession): Option[NormalizedURI] = {
    val hash = UrlHash.hashUrl(normalizedUrl)
    urlHashCache.getOrElseOpt(NormalizedURIUrlHashKey(hash)) {
      (for (t <- rows if t.urlHash === hash) yield t).firstOption
    }
  }

  def getByNormalizedUrls(normalizedUrls: Seq[String])(implicit session: RSession): Map[String, NormalizedURI] = {
    val hashes = normalizedUrls.map(UrlHash.hashUrl(_)).toSet
    val result = urlHashCache.bulkGetOrElseOpt(hashes map NormalizedURIUrlHashKey) { keys =>
      val urlHashes = keys.map(_.urlHash)
      val fetched = (for (t <- rows if t.urlHash.inSet(urlHashes)) yield t).list.map { u =>
        u.urlHash -> u
      }.toMap

      keys.map { k => k -> fetched.get(k.urlHash) }.toMap
    }
    result.collect { case (_, Some(uri)) => uri.url -> uri }
  }

  def getByUrlHash(urlHash: UrlHash)(implicit session: RSession): Option[NormalizedURI] = {
    urlHashCache.getOrElseOpt(NormalizedURIUrlHashKey(urlHash)) {
      rows.filter(row => row.urlHash === urlHash).firstOption
    }
  }

  def toBeRemigrated()(implicit session: RSession): Seq[NormalizedURI] =
    (for (t <- rows if t.state =!= NormalizedURIStates.REDIRECTED && t.redirect.isDefined) yield t).list

  def getByRedirection(redirect: Id[NormalizedURI])(implicit session: RSession): Seq[NormalizedURI] = {
    (for (t <- rows if t.state === NormalizedURIStates.REDIRECTED && t.redirect === redirect) yield t).list
  }

  def updateURIRestriction(id: Id[NormalizedURI], r: Option[Restriction])(implicit session: RWSession) = {
    val q = for { t <- rows if t.id === id } yield (t.restriction, t.seq)
    val newSeq = deferredSeqNum()
    q.update(r, newSeq)
    invalidateCache(get(id).copy(restriction = r, seq = newSeq))
  }

  def getRestrictedURIs(targetRestriction: Restriction)(implicit session: RSession): Seq[NormalizedURI] = {
    { for (r <- rows if r.restriction === targetRestriction) yield r }.list
  }

  def checkRecommendable(uriIds: Seq[Id[NormalizedURI]])(implicit session: RSession): Seq[Boolean] = {
    val info = { for (r <- rows if r.id.inSet(uriIds)) yield (r.id, r.restriction.isEmpty, r.state) }.list
    assert(info.size == uriIds.distinct.size, s"looks like some uriIds are missing in normalized_uri_repo")
    val m = info.map { case (id, noRestriction, state) => (id, noRestriction && (state == NormalizedURIStates.SCRAPED)) }.toMap
    uriIds.map { id => m(id) }
  }

  def getFromId(fromId: Id[NormalizedURI])(implicit session: RSession): Seq[NormalizedURI] = {
    (for (t <- rows if t.id > fromId) yield t).list
  }

  def getByRegex(regex: String)(implicit session: RSession): Seq[NormalizedURI] = {
    (for (u <- rows if (u.url like regex)) yield u).list
  }

  def getByIds(uriIds: Set[Id[NormalizedURI]])(implicit session: RSession): Map[Id[NormalizedURI], NormalizedURI] = {
    rows.filter(_.id.inSet(uriIds)).list.map(uri => uri.id.get -> uri).toMap
  }
}
