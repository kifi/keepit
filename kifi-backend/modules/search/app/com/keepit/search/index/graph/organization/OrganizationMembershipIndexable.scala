package com.keepit.search.index.graph.organization

import com.keepit.common.db.Id
import com.keepit.model.{ User, OrganizationMembershipStates, OrganizationMembership, IngestableOrganizationMembership }
import com.keepit.search.index.{ Searcher, Indexable, FieldDecoder }
import com.keepit.search.util.LongArraySet
import org.apache.lucene.index.Term

object OrganizationMembershipFields {
  val userField = "u"
  val userIdField = "uid"
  val orgField = "o"
  val orgIdField = "oid"

  val decoders: Map[String, FieldDecoder] = Map.empty
}

object OrganizationMembershipIndexable {
  import OrganizationMembershipFields._

  def getOrgsByMember(orgMemSearcher: Searcher, memberId: Id[User]): Set[Long] = {
    LongArraySet.from(orgMemSearcher.findSecondaryIds(new Term(userField, memberId.id.toString), orgIdField).toArray())
  }
}

class OrganizationMembershipIndexable(orgMem: IngestableOrganizationMembership) extends Indexable[OrganizationMembership, OrganizationMembership] {
  val id = orgMem.id
  val sequenceNumber = orgMem.seq
  val isDeleted: Boolean = (orgMem.state == OrganizationMembershipStates.INACTIVE)

  override def buildDocument = {
    import OrganizationMembershipFields._

    val doc = super.buildDocument

    doc.add(buildKeywordField(userField, orgMem.userId.id.toString))
    doc.add(buildIdValueField(userIdField, orgMem.userId))

    doc.add(buildKeywordField(orgField, orgMem.orgId.id.toString))
    doc.add(buildIdValueField(orgIdField, orgMem.orgId))

    doc
  }
}
