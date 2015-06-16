package com.keepit.model

import com.keepit.common.db.Id

final case class OrganizationFail(status: Int, message: String)
final case class MemberRemovals(failedToRemove: Set[Id[User]], removed: Set[Id[User]])
