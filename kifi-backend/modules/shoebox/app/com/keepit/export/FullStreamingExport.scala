package com.keepit.export

import com.keepit.common.concurrent.FutureHelpers
import com.keepit.model._
import com.keepit.rover.model.RoverUriSummary
import com.keepit.social.BasicUser
import play.api.libs.iteratee.{ Iteratee, Enumerator }
import com.keepit.common.core.futureExtensionOps
import play.api.libs.json.{ JsArray, OWrites, Json, Writes }

import scala.concurrent.{ ExecutionContext, Future }

object FullStreamingExport {
  final case class Root(user: BasicUser, spaces: Enumerator[SpaceExport])
  final case class SpaceExport(space: Either[BasicUser, BasicOrganization], libraries: Enumerator[LibraryExport])
  final case class LibraryExport(library: Library, keeps: Enumerator[KeepExport])
  final case class KeepExport(keep: Keep, uri: Option[RoverUriSummary])
}

