package com.keepit.graph

import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.graph.manager.{ KeepGraphUpdate, UserConnectionGraphUpdate, UserGraphUpdate }
import com.keepit.graph.model.{ UserConnectionSocialScore, UserConnectionFeedScore }
import com.keepit.model._

/**
 * Created by tanlin on 7/11/14.
 */
trait GraphTestHelper {
  val u42: Id[User] = Id[User](42)
  val u43: Id[User] = Id[User](43)
  val iid1: Id[NormalizedURI] = Id[NormalizedURI](1)
  val iid2: Id[NormalizedURI] = Id[NormalizedURI](2)
  val iid3: Id[NormalizedURI] = Id[NormalizedURI](3)
  val iid4: Id[NormalizedURI] = Id[NormalizedURI](4)

  val lid1: Id[URL] = Id[URL](1)
  val lid2: Id[URL] = Id[URL](2)
  val lid3: Id[URL] = Id[URL](3)
  val lid4: Id[URL] = Id[URL](4)

  val uid1: Id[User] = Id[User](11)
  val uid2: Id[User] = Id[User](12)
  val uid3: Id[User] = Id[User](13)

  val kid1: Id[Keep] = Id[Keep](1)
  val kid2: Id[Keep] = Id[Keep](2)
  val kid3: Id[Keep] = Id[Keep](3)
  val kid4: Id[Keep] = Id[Keep](4)

  val createUserUpdate = UserGraphUpdate(User(id = Some(u42), firstName = "Tan", lastName = "Lin", seq = SequenceNumber(1)))
  val createFirstDegreeUser = UserConnectionGraphUpdate(UserConnection(user1 = u42, user2 = u43, seq = SequenceNumber(2)))

  val keepGraphUpdate1 = KeepGraphUpdate(Keep(id = Some(kid1), uriId = iid1, urlId = lid1, url = "url1", userId = u43, source = KeepSource("site"), seq = SequenceNumber(3)))
  val keepGraphUpdate2 = KeepGraphUpdate(Keep(id = Some(kid2), uriId = iid2, urlId = lid2, url = "url2", userId = u43, source = KeepSource("site"), seq = SequenceNumber(4)))
  val keepGraphUpdate3 = KeepGraphUpdate(Keep(id = Some(kid3), uriId = iid3, urlId = lid3, url = "url3", userId = u43, source = KeepSource("site"), seq = SequenceNumber(5)))
  val keepGraphUpdate4 = KeepGraphUpdate(Keep(id = Some(kid4), uriId = iid4, urlId = lid4, url = "url4", userId = u43, source = KeepSource("site"), seq = SequenceNumber(6)))

  val userConnectionGraphUpdate1 = UserConnectionGraphUpdate(UserConnection(user1 = u42, user2 = uid1, seq = SequenceNumber(7)))
  val userConnectionGraphUpdate2 = UserConnectionGraphUpdate(UserConnection(user1 = u42, user2 = uid2, seq = SequenceNumber(8)))
  val userConnectionGraphUpdate3 = UserConnectionGraphUpdate(UserConnection(user1 = u42, user2 = uid3, seq = SequenceNumber(9)))
}
