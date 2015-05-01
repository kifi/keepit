package com.keepit.curator.model

import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.model.{ NormalizedURI, User, URISummary, Library }
import com.keepit.social.BasicUser
import com.keepit.common.crypto.PublicId

import com.kifi.macros.json

import play.api.libs.json.{ Json, Writes }

import org.joda.time.DateTime

@json case class RecoAttributionKind(value: String)

object RecoAttributionKind {
  object Keep extends RecoAttributionKind("keep")
  object Topic extends RecoAttributionKind("topic")
  object Library extends RecoAttributionKind("library")
}

@json case class RecoKind(value: String)

object RecoKind {
  object Keep extends RecoKind("keep")
  object Library extends RecoKind("library")
  object LibraryUpdates extends RecoKind("libraryUpdates")
}

@json case class RecoInfo(
  userId: Option[Id[User]], //who is this recommendation for
  uriId: Id[NormalizedURI], //what uri is being recommended
  score: Float, //the score of the uri
  explain: Option[String], //some explanation of the score, *not* meant to be seen by the user
  attribution: Option[SeedAttribution])

@json case class URIRecoResults(recos: Seq[RecoInfo], context: String)

