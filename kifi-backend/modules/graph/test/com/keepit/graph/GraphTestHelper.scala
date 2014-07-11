package com.keepit.graph

import com.keepit.common.db.Id
import com.keepit.graph.model.{ UserConnectionSocialScore, UserConnectionFeedScore }
import com.keepit.model.{ NormalizedURI, User }

/**
 * Created by tanlin on 7/11/14.
 */
trait GraphTestHelper {
  val u42: Id[User] = Id[User](42)

  val iid1: Id[NormalizedURI] = Id[NormalizedURI](1)
  val iid2: Id[NormalizedURI] = Id[NormalizedURI](2)
  val iid3: Id[NormalizedURI] = Id[NormalizedURI](3)
  val iid4: Id[NormalizedURI] = Id[NormalizedURI](4)

  val uid1: Id[User] = Id[User](1)
  val uid2: Id[User] = Id[User](2)
  val uid3: Id[User] = Id[User](3)
}
