package com.keepit.curator.model

import com.keepit.common.db.Id
import com.keepit.model.{ NormalizedURI, User }

import com.kifi.macros.json

@json case class RecommendationInfo(
  userId: Id[User], //who is this recommendation for
  uriId: Id[NormalizedURI], //what uri is being recommended
  score: Float, //the score of the uri
  explain: Option[String] //some explanation of the score, *not* meant to be seen by the user
  )
//this will gain more fields, in particular for attribution when the logic for that is there
