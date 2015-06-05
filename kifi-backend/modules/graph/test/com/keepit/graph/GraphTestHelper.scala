package com.keepit.graph

import com.keepit.common.db.{ SequenceNumber, Id, ExternalId }
import com.keepit.graph.manager._
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

  val userid1: Id[User] = Id[User](1)
  val userid2: Id[User] = Id[User](2)
  val userid3: Id[User] = Id[User](3)

  val keepid1: Id[Keep] = Id[Keep](1)
  val keepid2: Id[Keep] = Id[Keep](2)
  val keepid3: Id[Keep] = Id[Keep](3)
  val keepid4: Id[Keep] = Id[Keep](4)
  val keepid5: Id[Keep] = Id[Keep](5)

  val userUpdate = UserGraphUpdate(UserFactory.user().withId(u42).withName("Tan", "Lin").withUsername("test").withSeq(1).get)

  val keepGraphUpdate1 = KeepGraphUpdate(Keep(id = Some(keepid1), uriId = uriid1, urlId = urlid1, url = "url1", userId = u43,
    source = KeepSource("site"), seq = SequenceNumber(3), visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(Id[Library](1)), inDisjointLib = true))
  val keepGraphUpdate2 = KeepGraphUpdate(Keep(id = Some(keepid2), uriId = uriid2, urlId = urlid2, url = "url2", userId = u43,
    source = KeepSource("site"), seq = SequenceNumber(4), visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(Id[Library](1)), inDisjointLib = true))
  val keepGraphUpdate3 = KeepGraphUpdate(Keep(id = Some(keepid3), uriId = uriid3, urlId = urlid3, url = "url3", userId = u43,
    source = KeepSource("site"), seq = SequenceNumber(5), visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(Id[Library](1)), inDisjointLib = true))
  val keepGraphUpdate4 = KeepGraphUpdate(Keep(id = Some(keepid4), uriId = uriid4, urlId = urlid4, url = "url4", userId = u43,
    source = KeepSource("site"), seq = SequenceNumber(6), visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(Id[Library](1)), inDisjointLib = true))
  val keepGraphUpdate5 = KeepGraphUpdate(Keep(id = Some(keepid5), uriId = uriid5, urlId = urlid5, url = "url5", userId = u42,
    source = KeepSource("site"), seq = SequenceNumber(7), visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(Id[Library](1)), inDisjointLib = true))
  val keepUpdates = List(keepGraphUpdate1, keepGraphUpdate2, keepGraphUpdate3, keepGraphUpdate4, keepGraphUpdate5)

  val userConnectionGraphUpdate0 = UserConnectionGraphUpdate(UserConnection(user1 = u42, user2 = u43, seq = SequenceNumber(2)))
  val userConnectionGraphUpdate1 = UserConnectionGraphUpdate(UserConnection(user1 = u42, user2 = userid1, seq = SequenceNumber(8)))
  val userConnectionGraphUpdate2 = UserConnectionGraphUpdate(UserConnection(user1 = u42, user2 = userid2, seq = SequenceNumber(9)))
  val userConnectionGraphUpdate3 = UserConnectionGraphUpdate(UserConnection(user1 = u42, user2 = userid3, seq = SequenceNumber(10)))
  val userConnUpdates = List(userConnectionGraphUpdate0, userConnectionGraphUpdate1, userConnectionGraphUpdate2, userConnectionGraphUpdate3)

  val libMemUpdate1 = LibraryMembershipGraphUpdate(Id[User](1), Id[Library](1), LibraryMembershipStates.ACTIVE, SequenceNumber(1))
  val libMemUpdate2 = LibraryMembershipGraphUpdate(Id[User](1), Id[Library](2), LibraryMembershipStates.ACTIVE, SequenceNumber(2))
  val libMemUpdate3 = LibraryMembershipGraphUpdate(Id[User](1), Id[Library](3), LibraryMembershipStates.INACTIVE, SequenceNumber(3))
  val libMemUpdate4 = LibraryMembershipGraphUpdate(Id[User](2), Id[Library](1), LibraryMembershipStates.ACTIVE, SequenceNumber(4))
  val libMemUpdates = List(libMemUpdate1, libMemUpdate2, libMemUpdate3, libMemUpdate4)

  val allUpdates: List[GraphUpdate] = List(userUpdate) ++ keepUpdates ++ userConnUpdates ++ libMemUpdates
}
