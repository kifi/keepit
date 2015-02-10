package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.model.{ Keep, Library, User }

trait SocialPublishingCommander {
  def publishLibraryMembership(userId: Id[User], library: Library): Unit
  def publishKeep(userId: Id[User], keep: Keep, library: Library): Unit
}

class AllSocialPublishingCommander @Inject() (
    fb: FacebookPublishingCommander,
    twitter: TwitterPublishingCommander) {

  def publishLibraryMembership(userId: Id[User], library: Library): Unit = {
    fb.publishLibraryMembership(userId, library)
    twitter.publishLibraryMembership(userId, library)
  }

  def publishKeep(userId: Id[User], keep: Keep, library: Library): Unit = {
    fb.publishKeep(userId, keep, library)
    twitter.publishKeep(userId, keep, library)
  }

}
