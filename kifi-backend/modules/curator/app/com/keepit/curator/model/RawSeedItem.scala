package com.keepit.curator.model

import com.keepit.common.db.{ SequenceNumber, Model, Id, ModelWithSeqNumber }
import com.keepit.model.{ NormalizedURI, User }
import com.keepit.common.time._

import org.joda.time.DateTime

//will become active as soon as Library, Cortex etc. datatypes are finalized and available (some of these may be a while)
//Here for basic review
/*
case class LibraryInfo(
  libraryId: Id[Library]
  visibility: ???
  adder: Id[User]
)
case class AttributionInfo(
  relevantKeepers: Seq[Id[User]]
  relevantKeeps: Seq[Id[Keep]]
  relevantInterests: Seq[Interest]
)
*/

//unless otherwise noted, all relevant fields below also consider private keeps
//uniqueness constraint is on userId, uriId (yes, that has a flaw with MySQL null indexing, but better than nothing)
case class RawSeedItem(
  id: Option[Id[RawSeedItem]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  seq: SequenceNumber[RawSeedItem] = SequenceNumber.ZERO,
  uriId: Id[NormalizedURI],
  userId: Option[Id[User]], //which user is this a seed item for. None means this is for every user.
  firstKept: DateTime, //the first time anyone has kept this uri
  lastKept: DateTime, //the most recent time anyone has kept this uri
  lastSeen: DateTime, //the most recent time this uri was ingested for this user (could be due to a keep, a library addition, a graph output, ...); used for recency boost
  priorScore: Option[Float], //if the data source already scored the uri (e.g. when coming from the graph)
  timesKept: Int, //number of times this uri has been kept in total (note that with libraries allowing multiple keep per uri this can exceed the number of users who have kept the uri)
  // attributionInfo: AttributionInfo,
  // libraryInfo: Seq[LibraryInfo]
  discoverable: Boolean
  )
    extends Model[RawSeedItem] with ModelWithSeqNumber[RawSeedItem] {

  def withId(id: Id[RawSeedItem]): RawSeedItem = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): RawSeedItem = this.copy(updatedAt = updateTime)
}
