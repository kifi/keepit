package com.keepit.controllers.core

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.net.URI
import com.keepit.common.social.{UserWithSocial, UserWithSocialRepo}
import com.keepit.search.graph.URIGraph

import com.keepit.classify.{Domain, DomainClassifier, DomainRepo, DomainStates}

import com.keepit.model._

import com.google.inject.{Inject, Singleton}

case class SliderInfo(bookmark: Option[Bookmark], following: Boolean, socialUsers: Seq[UserWithSocial], numComments: Int, numMessages: Int, neverOnSite: Option[UserToDomain], sensitive: Option[Boolean])

@Singleton
class SliderInfoLoader @Inject() (db: Database,
  normalizedURIRepo: NormalizedURIRepo, domainRepo: DomainRepo, userToDomainRepo: UserToDomainRepo, socialConnectionRepo: SocialConnectionRepo, userRepo: UserRepo,
  followRepo: FollowRepo, bookmarkRepo: BookmarkRepo, commentRepo: CommentRepo,
  domainClassifier: DomainClassifier, uriGraph: URIGraph, userWithSocialRepo: UserWithSocialRepo) {

  def load(userId: Id[User], url: String): SliderInfo = db.readOnly {implicit s =>
    val nUri = normalizedURIRepo.getByNormalizedUrl(url)
    val host: Option[String] = URI.parse(url).get.host.map(_.name)
    val domain: Option[Domain] = host.flatMap(domainRepo.get(_))
    val neverOnSite: Option[UserToDomain] = domain.flatMap { domain =>
      userToDomainRepo.get(userId, domain.id.get, UserToDomainKinds.NEVER_SHOW)
    }
    val sensitive: Option[Boolean] = domain.flatMap(_.sensitive).orElse(host.flatMap(domainClassifier.isSensitive(_).right.toOption))

    nUri match {
      case Some(uri) =>
        val bookmark = bookmarkRepo.getByUriAndUser(uri.id.get, userId)
        val following = followRepo.get(userId, uri.id.get).isDefined

        val friendIds = socialConnectionRepo.getFortyTwoUserConnections(userId)
        val searcher = uriGraph.getURIGraphSearcher
        val friendEdgeSet = searcher.getUserToUserEdgeSet(userId, friendIds)
        val sharingUserIds = searcher.intersect(friendEdgeSet, searcher.getUriToUserEdgeSet(uri.id.get)).destIdSet - userId
        val socialUsers = sharingUserIds.map(u => userWithSocialRepo.toUserWithSocial(userRepo.get(u))).toSeq

        val numComments = commentRepo.getPublicCount(uri.id.get)
        val numMessages = commentRepo.getMessages(uri.id.get, userId).size

        SliderInfo(bookmark, following, socialUsers, numComments, numMessages, neverOnSite, sensitive)
      case None =>
        SliderInfo(None, false, Nil, 0, 0, neverOnSite, sensitive)
    }
  }
}