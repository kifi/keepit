package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.{ ExternalId, Id, State }
import org.apache.commons.lang3.RandomStringUtils.random

object PageInfoFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  def pageInfo(): PartialPageInfo = {
    new PartialPageInfo(PageInfo(id = Some(Id[PageInfo](-1 * idx.incrementAndGet())),
      uriId = Id[NormalizedURI](-1 * idx.incrementAndGet())))
  }

  def pageInfos(count: Int): Seq[PartialPageInfo] = List.fill(count)(pageInfo())

  class PartialPageInfo private[PageInfoFactory] (pageInfo: PageInfo) {
    def withId(id: Id[PageInfo]) = new PartialPageInfo(pageInfo.copy(id = Some(id)))
    def withId(id: Int) = new PartialPageInfo(pageInfo.copy(id = Some(Id[PageInfo](id))))
    def withUri(id: Id[NormalizedURI]) = new PartialPageInfo(pageInfo.copy(uriId = id))
    def withUri(id: Int) = new PartialPageInfo(pageInfo.copy(uriId = Id[NormalizedURI](id)))
    def withUri(uri: NormalizedURI) = new PartialPageInfo(pageInfo.copy(uriId = uri.id.get))
    def withTitle(title: String) = new PartialPageInfo(pageInfo.copy(title = Some(title)))
    def withTitle(title: Option[String]) = new PartialPageInfo(pageInfo.copy(title = title))
    def withDescription(description: String) = new PartialPageInfo(pageInfo.copy(description = Some(description)))
    def withState(state: State[PageInfo]) = new PartialPageInfo(pageInfo.copy(state = state))
    def get: PageInfo = pageInfo
  }

  implicit class PartialPageInfoSeq(pageInfos: Seq[PartialPageInfo]) {
    def get: Seq[PageInfo] = pageInfos.map(_.get)
  }

  implicit class PartialPageInfoFromKeep(keep: Keep) {
    def pageInfo: PartialPageInfo = PageInfoFactory.pageInfo().withTitle(keep.title).withUri(keep.uriId)
  }

}
