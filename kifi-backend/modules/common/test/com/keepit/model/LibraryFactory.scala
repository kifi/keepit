package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.{ ExternalId, Id, State }
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.model.LibraryVisibility.{ ORGANIZATION, PUBLISHED, SECRET, DISCOVERABLE }
import org.apache.commons.lang3.RandomStringUtils.randomAlphabetic

object LibraryFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  def library(): PartialLibrary = {
    new PartialLibrary(Library(id = Some(Id[Library](idx.incrementAndGet())), name = randomAlphabetic(5), slug = LibrarySlug(randomAlphabetic(5)),
      visibility = LibraryVisibility.SECRET, ownerId = Id[User](-idx.incrementAndGet()), memberCount = 1, keepCount = 0))
  }

  def libraries(count: Int): Seq[PartialLibrary] = List.fill(count)(library())

  case class PartialLibrary private[LibraryFactory] (
      library: Library,
      followers: Seq[User] = Seq.empty[User],
      collaborators: Seq[User] = Seq.empty[User],
      invitedUsers: Seq[User] = Seq.empty[User],
      invitedEmails: Seq[EmailAddress] = Seq.empty[EmailAddress]) {
    def withId(id: Id[Library]) = this.copy(library = library.copy(id = Some(id)))
    def withId(id: Int) = this.copy(library = library.copy(id = Some(Id[Library](id))))
    def withOwner(id: Id[User]) = this.copy(library = library.copy(ownerId = id))
    def withOwner(user: User) = this.copy(library = library.copy(ownerId = user.id.get))
    def withMemberCount(memberCount: Int) = this.copy(library = library.copy(memberCount = memberCount, lastKept = Some(currentDateTime)))
    def withKeepCount(keepCount: Int) = this.copy(library = library.copy(keepCount = keepCount))
    def withName(name: String) = this.copy(library = library.copy(name = name))
    def withDesc(desc: String) = this.copy(library = library.copy(description = Some(desc)))
    def withSlug(slug: String) = this.copy(library = library.copy(slug = LibrarySlug(slug)))
    def withColor(color: String): PartialLibrary = withColor(LibraryColor(color))
    def withColor(color: LibraryColor) = this.copy(library = library.copy(color = Some(color)))
    def withKind(kind: LibraryKind) = kind match {
      case LibraryKind.SYSTEM_MAIN => this.copy(library = library.copy(kind = LibraryKind.SYSTEM_MAIN, visibility = LibraryVisibility.DISCOVERABLE))
      case LibraryKind.SYSTEM_SECRET => this.copy(library = library.copy(kind = LibraryKind.SYSTEM_SECRET, visibility = LibraryVisibility.SECRET))
      case LibraryKind.USER_CREATED => this.copy(library = library.copy(kind = LibraryKind.USER_CREATED))
      case LibraryKind.SYSTEM_ORG_GENERAL => this.copy(library = library.copy(kind = LibraryKind.SYSTEM_ORG_GENERAL))
      case LibraryKind.SLACK_CHANNEL => this.copy(library = library.copy(kind = LibraryKind.SLACK_CHANNEL))
    }
    def withState(state: State[Library]) = this.copy(library = library.copy(state = state))
    def withVisibility(viz: LibraryVisibility) = this.copy(library = library.copy(visibility = viz))
    def secret() = this.copy(library = library.copy(visibility = SECRET))
    def published() = this.copy(library = library.copy(visibility = PUBLISHED))
    def discoverable() = this.copy(library = library.copy(visibility = DISCOVERABLE))
    def orgVisible() = this.copy(library = library.copy(visibility = ORGANIZATION))
    def withLastKept() = this.copy(library = library.copy(lastKept = Some(currentDateTime)))
    def withOrganizationIdOpt(id: Option[Id[Organization]]) = this.copy(library = library.copy(organizationId = id))
    def withOrganization(org: Organization) = this.copy(library = library.copy(organizationId = Some(org.id.get), visibility = LibraryVisibility.ORGANIZATION))
    def withOrgMemberCollaborativePermission(access: Option[LibraryAccess]) = this.copy(library = library.copy(organizationMemberAccess = access))
    def withLibraryCommentPermissions(permission: LibraryCommentPermissions) = this.copy(library = library.copy(whoCanComment = permission))
    def withFollowers(users: Seq[User]) = this.copy(followers = users)
    def withCollaborators(users: Seq[User]) = this.copy(collaborators = users)
    def withInvitedUsers(users: Seq[User]) = this.copy(invitedUsers = users)
    def withInvitedEmails(emails: Seq[EmailAddress]) = this.copy(invitedEmails = emails)

    def get: Library = library
  }

  implicit class PartialLibrarySeq(users: Seq[PartialLibrary]) {
    def get: Seq[Library] = users.map(_.get)
  }

}
