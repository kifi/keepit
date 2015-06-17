package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.{ SequenceNumber, ExternalId, Id, State }
import com.keepit.common.time._
import org.apache.commons.lang3.RandomStringUtils.random
import org.joda.time.DateTime

object KeepFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  def keep(): PartialKeep = {
    val userId = Id[User](-1 * idx.incrementAndGet())
    new PartialKeep(Keep(id = Some(Id[Keep](-1 * idx.incrementAndGet())),
      uriId = Id[NormalizedURI](-1 * idx.incrementAndGet()),
      inDisjointLib = true,
      urlId = Id[URL](-1 * idx.incrementAndGet()),
      url = s"http://${random(5, "abcdefghijklmnopqrstuvwxyz")}.com/${random(5, "abcdefghijklmnopqrstuvwxyz")}",
      visibility = LibraryVisibility.PUBLISHED,
      userId = userId,
      source = KeepSource.keeper,
      libraryId = None,
      note = None,
      originalKeeperId = Some(userId)
    ))
  }

  def keeps(count: Int): Seq[PartialKeep] = List.fill(count)(keep())

  class PartialKeep private[KeepFactory] (keep: Keep) {
    def withUser(id: Id[User]) = new PartialKeep(keep.copy(userId = id))
    def withUser(user: User) = new PartialKeep(keep.copy(userId = user.id.get))
    def withKeptAt(keptAt: DateTime) = new PartialKeep(keep.copy(keptAt = keptAt))
    def withId(id: Id[Keep]) = new PartialKeep(keep.copy(id = Some(id)))
    def withId(id: Int) = new PartialKeep(keep.copy(id = Some(Id[Keep](id))))
    def withId(id: ExternalId[Keep]) = new PartialKeep(keep.copy(externalId = id))
    def withId(id: String) = new PartialKeep(keep.copy(externalId = ExternalId[Keep](id)))
    def withTitle(title: String) = new PartialKeep(keep.copy(title = Some(title)))
    def withVisibility(visibility: LibraryVisibility) = new PartialKeep(keep.copy(visibility = visibility))
    def discoverable() = new PartialKeep(keep.copy(visibility = LibraryVisibility.DISCOVERABLE))
    def published() = new PartialKeep(keep.copy(visibility = LibraryVisibility.PUBLISHED))
    def secret() = new PartialKeep(keep.copy(visibility = LibraryVisibility.SECRET))
    def withLibrary(library: Library) = new PartialKeep(keep.copy(libraryId = library.id, userId = library.ownerId, visibility = library.visibility))
    def withLibrary(library: Id[Library]) = new PartialKeep(keep.copy(libraryId = Some(library)))
    def withNote(note: Option[String]) = new PartialKeep(keep.copy(note = note))
    def withState(state: State[Keep]) = new PartialKeep(keep.copy(state = state))
    def withOrganizationId(orgId: Option[Id[Organization]]) = new PartialKeep(keep.copy(organizationId = orgId))
    def get: Keep = keep
  }

  implicit class PartialKeepSeq(keeps: Seq[PartialKeep]) {
    def get: Seq[Keep] = keeps.map(_.get)
  }

}
