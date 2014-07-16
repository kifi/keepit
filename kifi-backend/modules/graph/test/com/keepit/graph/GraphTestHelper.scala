package com.keepit.graph

import com.keepit.common.db.{ SequenceNumber, Id, ExternalId }
import com.keepit.graph.manager.{ KeepGraphUpdate, UserConnectionGraphUpdate, UserGraphUpdate }
import com.keepit.graph.model.{ ConnectedUserScore, ConnectedUriScore }
import com.keepit.model._

trait GraphTestHelper {
  val u42: Id[User] = Id[User](42)
  val u43: Id[User] = Id[User](43)

  val uriid1: Id[NormalizedURI] = Id[NormalizedURI](1)
  val uriid2: Id[NormalizedURI] = Id[NormalizedURI](2)
  val uriid3: Id[NormalizedURI] = Id[NormalizedURI](3)
  val uriid4: Id[NormalizedURI] = Id[NormalizedURI](4)
  val uriid5: Id[NormalizedURI] = Id[NormalizedURI](5)

  val urlid1: Id[URL] = Id[URL](1)
  val urlid2: Id[URL] = Id[URL](2)
  val urlid3: Id[URL] = Id[URL](3)
  val urlid4: Id[URL] = Id[URL](4)
  val urlid5: Id[URL] = Id[URL](5)

  val userid1: Id[User] = Id[User](11)
  val userid2: Id[User] = Id[User](12)
  val userid3: Id[User] = Id[User](13)

  val keepid1: Id[Keep] = Id[Keep](1)
  val keepid2: Id[Keep] = Id[Keep](2)
  val keepid3: Id[Keep] = Id[Keep](3)
  val keepid4: Id[Keep] = Id[Keep](4)
  val keepid5: Id[Keep] = Id[Keep](5)

  val createUserUpdate = UserGraphUpdate(User(id = Some(u42), firstName = "Tan", lastName = "Lin", seq = SequenceNumber(1)))
  val createFirstDegreeUser = UserConnectionGraphUpdate(UserConnection(user1 = u42, user2 = u43, seq = SequenceNumber(2)))

  val keepGraphUpdate1 = KeepGraphUpdate(Keep(id = Some(keepid1), uriId = uriid1, urlId = urlid1, url = "url1", userId = u43, source = KeepSource("site"), seq = SequenceNumber(3), libraryId = Some(Id[Library](1)), libraryExternalId = Some(ExternalId[Library]())))
  val keepGraphUpdate2 = KeepGraphUpdate(Keep(id = Some(keepid2), uriId = uriid2, urlId = urlid2, url = "url2", userId = u43, source = KeepSource("site"), seq = SequenceNumber(4), libraryId = Some(Id[Library](1)), libraryExternalId = Some(ExternalId[Library]())))
  val keepGraphUpdate3 = KeepGraphUpdate(Keep(id = Some(keepid3), uriId = uriid3, urlId = urlid3, url = "url3", userId = u43, source = KeepSource("site"), seq = SequenceNumber(5), libraryId = Some(Id[Library](1)), libraryExternalId = Some(ExternalId[Library]())))
  val keepGraphUpdate4 = KeepGraphUpdate(Keep(id = Some(keepid4), uriId = uriid4, urlId = urlid4, url = "url4", userId = u43, source = KeepSource("site"), seq = SequenceNumber(6), libraryId = Some(Id[Library](1)), libraryExternalId = Some(ExternalId[Library]())))
  val keepGraphUpdate5 = KeepGraphUpdate(Keep(id = Some(keepid5), uriId = uriid5, urlId = urlid5, url = "url5", userId = u42, source = KeepSource("site"), seq = SequenceNumber(7), libraryId = Some(Id[Library](1)), libraryExternalId = Some(ExternalId[Library]())))

  val userConnectionGraphUpdate1 = UserConnectionGraphUpdate(UserConnection(user1 = u42, user2 = userid1, seq = SequenceNumber(8)))
  val userConnectionGraphUpdate2 = UserConnectionGraphUpdate(UserConnection(user1 = u42, user2 = userid2, seq = SequenceNumber(9)))
  val userConnectionGraphUpdate3 = UserConnectionGraphUpdate(UserConnection(user1 = u42, user2 = userid3, seq = SequenceNumber(10)))
}
