package com.keepit.graph.manager

import com.keepit.abook.model.{ EmailAccountInfo, IngestableContact }
import com.keepit.classify.{ DomainInfo, Domain }
import com.keepit.common.db.{ Id, SequenceNumber, State }
import com.keepit.common.reflection.CompanionTypeSystem
import com.keepit.common.service.IpAddress
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.{ DenseLDA, UriSparseLDAFeatures }
import com.keepit.model._
import com.keepit.shoebox.model.IngestableUserIpAddress
import com.keepit.model.view.LibraryMembershipView
import com.keepit.social.SocialNetworkType
import org.joda.time.DateTime

sealed trait GraphUpdate { self =>
  type U >: self.type <: GraphUpdate
  def seq: SequenceNumber[U]
  def kind: GraphUpdateKind[U]
  def instance: U = self
}

sealed trait GraphUpdateKind[U <: GraphUpdate] {
  def code: String
  def seq(value: Long): SequenceNumber[U] = SequenceNumber(value)
}

object GraphUpdateKind {
  val all: Set[GraphUpdateKind[_ <: GraphUpdate]] = CompanionTypeSystem[GraphUpdate, GraphUpdateKind[_ <: GraphUpdate]]("U")
  private val byCode: Map[String, GraphUpdateKind[_ <: GraphUpdate]] = {
    require(all.size == all.map(_.code).size, "Duplicate GraphUpdateKind names.")
    all.map { kind => kind.code -> kind }.toMap
  }
  def apply(code: String): GraphUpdateKind[_ <: GraphUpdate] = byCode(code)
}

case class UserGraphUpdate(userId: Id[User], state: State[User], userSeq: SequenceNumber[User]) extends GraphUpdate {
  type U = UserGraphUpdate
  def kind = UserGraphUpdate
  def seq = kind.seq(userSeq.value)
}

case object UserGraphUpdate extends GraphUpdateKind[UserGraphUpdate] {
  val code = "user_graph_update"
  def apply(user: User): UserGraphUpdate = UserGraphUpdate(user.id.get, user.state, user.seq)
}

case class UserConnectionGraphUpdate(firstUserId: Id[User], secondUserId: Id[User], state: State[UserConnection], userConnectionSeq: SequenceNumber[UserConnection]) extends GraphUpdate {
  type U = UserConnectionGraphUpdate
  def kind = UserConnectionGraphUpdate
  def seq = kind.seq(userConnectionSeq.value)
}

case object UserConnectionGraphUpdate extends GraphUpdateKind[UserConnectionGraphUpdate] {
  val code = "user_connection_graph_update"
  def apply(userConnection: UserConnection): UserConnectionGraphUpdate = UserConnectionGraphUpdate(userConnection.user1, userConnection.user2, userConnection.state, userConnection.seq)
}

case class SocialUserInfoGraphUpdate(socialUserId: Id[SocialUserInfo], network: SocialNetworkType, userId: Option[Id[User]], socialUserSeq: SequenceNumber[SocialUserInfo]) extends GraphUpdate {
  type U = SocialUserInfoGraphUpdate
  def kind = SocialUserInfoGraphUpdate
  def seq = kind.seq(socialUserSeq.value)
}

case object SocialUserInfoGraphUpdate extends GraphUpdateKind[SocialUserInfoGraphUpdate] {
  val code = "social_user_info_graph_update"
  def apply(sui: SocialUserInfo): SocialUserInfoGraphUpdate = SocialUserInfoGraphUpdate(sui.id.get, sui.networkType, sui.userId, sui.seq)
}

case class SocialConnectionGraphUpdate(firstSocialUserId: Id[SocialUserInfo], secondSocialUserId: Id[SocialUserInfo], network: SocialNetworkType, state: State[SocialConnection], socialConnectionSeq: SequenceNumber[SocialConnection]) extends GraphUpdate {
  type U = SocialConnectionGraphUpdate
  def kind = SocialConnectionGraphUpdate
  def seq = kind.seq(socialConnectionSeq.value)
}

case object SocialConnectionGraphUpdate extends GraphUpdateKind[SocialConnectionGraphUpdate] {
  val code = "social_connection_graph_update"
  def apply(connection: IndexableSocialConnection): SocialConnectionGraphUpdate = SocialConnectionGraphUpdate(connection.firstSocialUserId, connection.secondSocialUserId, connection.network, connection.state, connection.seq)
}

case class KeepGraphUpdate(id: Id[Keep], userId: Id[User], uriId: Id[NormalizedURI], libId: Id[Library], state: State[Keep], source: KeepSource, createdAt: DateTime, keepSeq: SequenceNumber[Keep]) extends GraphUpdate {
  type U = KeepGraphUpdate
  def kind = KeepGraphUpdate
  def seq = kind.seq(keepSeq.value)
}

case object KeepGraphUpdate extends GraphUpdateKind[KeepGraphUpdate] {
  val code = "keep_graph_update"
  def apply(keep: Keep): KeepGraphUpdate = KeepGraphUpdate(keep.id.get, keep.userId, keep.uriId, keep.libraryId.get, keep.state, keep.source, keep.createdAt, keep.seq)
}

case class SparseLDAGraphUpdate(modelVersion: ModelVersion[DenseLDA], uriFeatures: UriSparseLDAFeatures) extends GraphUpdate {
  type U = SparseLDAGraphUpdate
  def kind = SparseLDAGraphUpdate
  val seq = kind.seq(CortexSequenceNumber(modelVersion, uriFeatures.uriSeq).toLong)
}

case object SparseLDAGraphUpdate extends GraphUpdateKind[SparseLDAGraphUpdate] {
  val code = "sparse_lda_graph_update"
}

case class LDAOldVersionCleanupGraphUpdate(modelVersion: ModelVersion[DenseLDA], numTopics: Int) extends GraphUpdate {
  type U = LDAOldVersionCleanupGraphUpdate
  def kind = LDAOldVersionCleanupGraphUpdate
  val seq = kind.seq(modelVersion.version.toLong)
}

case object LDAOldVersionCleanupGraphUpdate extends GraphUpdateKind[LDAOldVersionCleanupGraphUpdate] {
  val code = "lda_old_version_cleanup_graph_update"
}

case class NormalizedUriGraphUpdate(id: Id[NormalizedURI], domainId: Option[Id[Domain]], state: State[NormalizedURI], uriSeq: SequenceNumber[NormalizedURI]) extends GraphUpdate {
  type U = NormalizedUriGraphUpdate
  def kind = NormalizedUriGraphUpdate
  def seq = kind.seq(uriSeq.value)
}

case object NormalizedUriGraphUpdate extends GraphUpdateKind[NormalizedUriGraphUpdate] {
  val code = "normalized_uri_graph_update"
  def apply(indexableUri: IndexableUri, domainId: Option[Id[Domain]]): NormalizedUriGraphUpdate = NormalizedUriGraphUpdate(indexableUri.id.get, domainId, indexableUri.state, indexableUri.seq)
}

case class EmailAccountGraphUpdate(emailAccountId: Id[EmailAccountInfo], userId: Option[Id[User]], domainId: Option[Id[Domain]], verified: Boolean, emailSeq: SequenceNumber[EmailAccountInfo]) extends GraphUpdate {
  type U = EmailAccountGraphUpdate
  def kind = EmailAccountGraphUpdate
  def seq = kind.seq(emailSeq.value)
}

case object EmailAccountGraphUpdate extends GraphUpdateKind[EmailAccountGraphUpdate] {
  val code = "email_account_graph_update"
  def apply(emailAccount: EmailAccountInfo, domainId: Option[Id[Domain]]): EmailAccountGraphUpdate = {
    EmailAccountGraphUpdate(emailAccount.emailAccountId, emailAccount.userId, domainId, emailAccount.verified, emailAccount.seq)
  }
}

case class EmailContactGraphUpdate(userId: Id[User], abookId: Id[ABookInfo], emailAccountId: Id[EmailAccountInfo], hidden: Boolean, deleted: Boolean, emailcontactSeq: SequenceNumber[IngestableContact]) extends GraphUpdate {
  type U = EmailContactGraphUpdate
  def kind = EmailContactGraphUpdate
  def seq = kind.seq(emailcontactSeq.value)
}

case object EmailContactGraphUpdate extends GraphUpdateKind[EmailContactGraphUpdate] {
  val code = "email_contact_graph_update"
  def apply(contact: IngestableContact): EmailContactGraphUpdate = {
    EmailContactGraphUpdate(contact.userId, contact.abookId, contact.emailAccountId, contact.hidden, contact.deleted, contact.seq)
  }
}

case class LibraryMembershipGraphUpdate(userId: Id[User], libId: Id[Library], state: State[LibraryMembership], libMemSeq: SequenceNumber[LibraryMembership]) extends GraphUpdate {
  type U = LibraryMembershipGraphUpdate
  def kind = LibraryMembershipGraphUpdate
  def seq = kind.seq(libMemSeq.value)
}

case object LibraryMembershipGraphUpdate extends GraphUpdateKind[LibraryMembershipGraphUpdate] {
  val code = "library_membership_graph_update"
  def apply(libView: LibraryMembershipView): LibraryMembershipGraphUpdate = LibraryMembershipGraphUpdate(libView.userId, libView.libraryId, libView.state, libView.seq)
}

case class LibraryGraphUpdate(libId: Id[Library], state: State[Library], libSeq: SequenceNumber[Library]) extends GraphUpdate {
  type U = LibraryGraphUpdate
  def kind = LibraryGraphUpdate
  def seq = kind.seq(libSeq.value)
}

case object LibraryGraphUpdate extends GraphUpdateKind[LibraryGraphUpdate] {
  val code = "library_graph_update"
  def apply(libView: LibraryView): LibraryGraphUpdate = LibraryGraphUpdate(libView.id.get, libView.state, libView.seq)
}

case class OrganizationGraphUpdate(orgId: Id[Organization], state: State[Organization], orgSeq: SequenceNumber[Organization]) extends GraphUpdate {
  type U = OrganizationGraphUpdate
  def kind = OrganizationGraphUpdate
  def seq = kind.seq(orgSeq.value)
}

case object OrganizationGraphUpdate extends GraphUpdateKind[OrganizationGraphUpdate] {
  val code = "organization_graph_update"
  def apply(org: IngestableOrganization): OrganizationGraphUpdate = OrganizationGraphUpdate(org.id.get, org.state, org.seq)
}

case class OrganizationMembershipGraphUpdate(orgId: Id[Organization], userId: Id[User], createdAt: DateTime, state: State[OrganizationMembership], orgMemSeq: SequenceNumber[OrganizationMembership]) extends GraphUpdate {
  type U = OrganizationMembershipGraphUpdate
  def kind = OrganizationMembershipGraphUpdate
  def seq = kind.seq(orgMemSeq.value)
}

case object OrganizationMembershipGraphUpdate extends GraphUpdateKind[OrganizationMembershipGraphUpdate] {
  val code = "organization_membership_graph_update"
  def apply(orgMem: IngestableOrganizationMembership): OrganizationMembershipGraphUpdate = OrganizationMembershipGraphUpdate(orgMem.orgId, orgMem.userId, orgMem.createdAt, orgMem.state, orgMem.seq)
}

case class OrganizationMembershipCandidateGraphUpdate(orgId: Id[Organization], userId: Id[User], createdAt: DateTime, state: State[OrganizationMembershipCandidate], orgMemSeq: SequenceNumber[OrganizationMembershipCandidate]) extends GraphUpdate {
  type U = OrganizationMembershipCandidateGraphUpdate
  def kind = OrganizationMembershipCandidateGraphUpdate
  def seq = kind.seq(orgMemSeq.value)
}

case object OrganizationMembershipCandidateGraphUpdate extends GraphUpdateKind[OrganizationMembershipCandidateGraphUpdate] {
  val code = "organization_membership_candidate_graph_update"
  def apply(orgMemCandidate: IngestableOrganizationMembershipCandidate): OrganizationMembershipCandidateGraphUpdate = OrganizationMembershipCandidateGraphUpdate(orgMemCandidate.orgId, orgMemCandidate.userId, orgMemCandidate.createdAt, orgMemCandidate.state, orgMemCandidate.seq)
}

case class UserIpAddressGraphUpdate(userId: Id[User], ipAddress: IpAddress, updatedAt: DateTime, ipSeq: SequenceNumber[IngestableUserIpAddress]) extends GraphUpdate {
  type U = UserIpAddressGraphUpdate
  def kind = UserIpAddressGraphUpdate
  def seq = kind.seq(ipSeq.value)
}

case object UserIpAddressGraphUpdate extends GraphUpdateKind[UserIpAddressGraphUpdate] {
  val code = "user_ip_addr_update"
  def apply(userIpAddress: IngestableUserIpAddress): UserIpAddressGraphUpdate = UserIpAddressGraphUpdate(userIpAddress.userId, userIpAddress.ipAddress, userIpAddress.updatedAt, userIpAddress.seqNum)
}

case class OrganizationDomainOwnershipGraphUpdate(orgId: Id[Organization], domainId: Id[Domain], state: State[OrganizationDomainOwnership], orgDomainOwnSeq: SequenceNumber[OrganizationDomainOwnership]) extends GraphUpdate {
  type U = OrganizationDomainOwnershipGraphUpdate
  def kind: GraphUpdateKind[U] = OrganizationDomainOwnershipGraphUpdate
  def seq: SequenceNumber[U] = kind.seq(orgDomainOwnSeq.value)
}

case object OrganizationDomainOwnershipGraphUpdate extends GraphUpdateKind[OrganizationDomainOwnershipGraphUpdate] {
  val code = "organization_domain_ownership_update"
  def apply(orgDomainOwn: IngestableOrganizationDomainOwnership): OrganizationDomainOwnershipGraphUpdate = OrganizationDomainOwnershipGraphUpdate(orgDomainOwn.organizationId, orgDomainOwn.domainId, orgDomainOwn.state, orgDomainOwn.seq)
}
