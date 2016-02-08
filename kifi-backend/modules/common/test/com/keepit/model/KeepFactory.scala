package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.{ SequenceNumber, ExternalId, Id, State }
import com.keepit.common.time._
import org.apache.commons.lang3.RandomStringUtils
import org.apache.commons.lang3.RandomStringUtils.random
import org.joda.time.DateTime

object KeepFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  def keep(): PartialKeep = {
    val userId = Id[User](-1 * idx.incrementAndGet())
    new PartialKeep(Keep(id = Some(Id[Keep](-1 * idx.incrementAndGet())),
      uriId = Id[NormalizedURI](-1 * idx.incrementAndGet()),
      url = s"http://${random(5, "abcdefghijklmnopqrstuvwxyz")}.com/${random(5, "abcdefghijklmnopqrstuvwxyz")}",
      visibility = LibraryVisibility.SECRET,
      title = None,
      keptAt = currentDateTime.minusYears(10).plusMinutes(idx.incrementAndGet().toInt),
      userId = Some(userId),
      source = KeepSource.keeper,
      libraryId = None,
      note = None,
      connections = KeepConnections(Set.empty, Set(userId)),
      originalKeeperId = Some(userId),
      lastActivityAt = currentDateTime.minusYears(10).plusMinutes(idx.incrementAndGet().toInt)
    ))
  }

  def keeps(count: Int): Seq[PartialKeep] = List.fill(count)(keep())

  case class PartialKeep private[KeepFactory] (keep: Keep) {
    def withUser(id: Id[User]) = this.copy(keep = keep.copy(userId = Some(id), connections = keep.connections.withUsers(Set(id))))
    def withUser(user: User) = this.copy(keep = keep.copy(userId = Some(user.id.get), connections = keep.connections.withUsers(Set(user.id.get))))
    def withCreatedAt(time: DateTime) = this.copy(keep = keep.copy(createdAt = time))
    def withKeptAt(time: DateTime) = this.copy(keep = keep.copy(keptAt = time))
    def withId(id: Id[Keep]) = this.copy(keep = keep.copy(id = Some(id)))
    def withId(id: Int) = this.copy(keep = keep.copy(id = Some(Id[Keep](id))))
    def withId(id: ExternalId[Keep]) = this.copy(keep = keep.copy(externalId = id))
    def withId(id: String) = this.copy(keep = keep.copy(externalId = ExternalId[Keep](id)))
    def withTitle(title: String) = this.copy(keep = keep.copy(title = Some(title)))
    def withRandomTitle() = this.copy(keep = keep.copy(title = Some(RandomStringUtils.randomAlphabetic(25))))
    def withSource(ks: KeepSource) = this.copy(keep = keep.copy(source = ks))
    def withSeq(seq: SequenceNumber[Keep]) = this.copy(keep = keep.copy(seq = seq))
    def withVisibility(visibility: LibraryVisibility) = this.copy(keep = keep.copy(visibility = visibility))
    def discoverable() = this.copy(keep = keep.copy(visibility = LibraryVisibility.DISCOVERABLE))
    def published() = this.copy(keep = keep.copy(visibility = LibraryVisibility.PUBLISHED))
    def secret() = this.copy(keep = keep.copy(visibility = LibraryVisibility.SECRET))
    def withLibrary(library: Library) = this.copy(keep = keep.withLibrary(library))
    def withLibrary(library: Id[Library]) = this.copy(keep = keep.copy(libraryId = Some(library)))
    def withNote(note: String) = this.copy(keep = keep.copy(note = Some(note)))
    def withState(state: State[Keep]) = this.copy(keep = keep.copy(state = state))
    def withOrganizationId(orgId: Option[Id[Organization]]) = this.copy(keep = keep.copy(organizationId = orgId))
    def withURIId(id: Id[NormalizedURI]) = this.copy(keep = keep.copy(uriId = id))
    def withUri(uri: NormalizedURI) = this.copy(keep = keep.copy(uriId = uri.id.get, url = uri.url))
    def withUrl(url: String) = this.copy(keep = keep.copy(url = url))
    def withLastActivityAt(time: DateTime) = this.copy(keep = keep.copy(lastActivityAt = time))
    def get: Keep = keep
  }

  implicit class PartialKeepSeq(keeps: Seq[PartialKeep]) {
    def get: Seq[Keep] = keeps.map(_.get)
  }

}
