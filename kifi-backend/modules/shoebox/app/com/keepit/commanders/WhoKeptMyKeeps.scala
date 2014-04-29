package com.keepit.commanders

import com.keepit.common.db.Id
import org.joda.time.{LocalDate, DateTime}
import com.keepit.model.{User, NormalizedURI}
import com.keepit.social.BasicUser

case class WhoKeptMyKeeps(count: Int, latestKeep: DateTime, uri: Id[NormalizedURI], users: Seq[Id[User]])
case class RichWhoKeptMyKeeps(count: Int, latestKeep: LocalDate, uri: NormalizedURI, users: Seq[User])

