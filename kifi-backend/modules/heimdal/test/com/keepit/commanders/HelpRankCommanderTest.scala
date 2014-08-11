package com.keepit.commanders

import com.keepit.commander.{ AttributionCommander, HelpRankCommander }
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.db.{ ExternalId, Id, SequenceNumber }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.time._
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.{ KifiHitContext, SanitizedKifiHit }
import com.keepit.model._
import com.keepit.search.ArticleSearchResult
import com.keepit.shoebox.{ FakeShoeboxServiceClientImpl, FakeShoeboxServiceModule, ShoeboxServiceClient }
import com.keepit.test._
import net.codingwell.scalaguice.ScalaModule
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions

import scala.concurrent.Await
import scala.concurrent.duration._

class HelpRankCommanderTest extends Specification with HeimdalTestInjector with NoTimeConversions {

  implicit val execCtx = ExecutionContext.fj

  def mkKeep(userId: Id[User], tsOpt: Option[DateTime] = None, libraryId: Option[Id[Library]] = None)(id: Id[Keep], uri: NormalizedURI): Keep = {
    val ts = tsOpt getOrElse currentDateTime
    Keep(id = Some(id), createdAt = ts, updatedAt = ts, uriId = uri.id.get, url = uri.url, urlId = Id[URL](uri.id.get.id), isPrivate = false, userId = userId, source = KeepSource.keeper, libraryId = libraryId)
  }

  def mkURI(id: Id[NormalizedURI], url: String, seq: SequenceNumber[NormalizedURI], tsOpt: Option[DateTime] = None): NormalizedURI = {
    val ts = tsOpt getOrElse currentDateTime
    NormalizedURI(id = Some(id), createdAt = ts, updatedAt = ts, url = url, urlHash = NormalizedURI.hashUrl(url), seq = seq)
  }

  def modules: Seq[ScalaModule] = Seq(
    FakeHttpClientModule(),
    FakeShoeboxServiceModule()
  )

  "HelpRankCommander" should {

    "track discoveries & rekeeps" in {
      withDb(modules: _*) { implicit injector =>
        val ts = currentDateTime
        val u1 = User(id = Some(Id[User](1)), createdAt = ts, updatedAt = ts, firstName = "Shanee", lastName = "Smith")
        val u2 = User(id = Some(Id[User](2)), createdAt = ts, updatedAt = ts, firstName = "Foo", lastName = "Bar")
        val u3 = User(id = Some(Id[User](3)), createdAt = ts, updatedAt = ts, firstName = "Ping", lastName = "Pong")
        val u4 = User(id = Some(Id[User](4)), createdAt = ts, updatedAt = ts, firstName = "Ro", lastName = "Bot")

        val uri42 = mkURI(Id[NormalizedURI](1), "http://42go.com", SequenceNumber[NormalizedURI](1))
        val uriKifi = mkURI(Id[NormalizedURI](2), "http://kifi.com", SequenceNumber[NormalizedURI](2))
        val uriGoog = mkURI(Id[NormalizedURI](3), "http://google.com", SequenceNumber[NormalizedURI](3))
        val uriBing = mkURI(Id[NormalizedURI](4), "http://bing.com", SequenceNumber[NormalizedURI](4))
        val uriStanford = mkURI(Id[NormalizedURI](5), "http://stanford.edu", SequenceNumber[NormalizedURI](5))
        val uriApple = mkURI(Id[NormalizedURI](6), "http://apple.com", SequenceNumber[NormalizedURI](6))

        val mkKeep1 = mkKeep(u1.id.get, Some(currentDateTime)) _
        val keeps1 = Seq(
          mkKeep1(Id[Keep](1), uri42),
          mkKeep1(Id[Keep](2), uriKifi)
        )
        val mkKeep2 = mkKeep(u2.id.get, Some(currentDateTime)) _
        val keeps2 = Seq(
          mkKeep2(Id[Keep](3), uriKifi),
          mkKeep2(Id[Keep](4), uriGoog),
          mkKeep2(Id[Keep](5), uriBing)
        )
        val mkKeep3 = mkKeep(u3.id.get, Some(currentDateTime)) _
        val keeps3 = Seq(
          mkKeep2(Id[Keep](6), uriKifi),
          mkKeep2(Id[Keep](7), uriStanford),
          mkKeep2(Id[Keep](8), uriBing)
        )
        val mkKeep4 = mkKeep(u3.id.get, Some(currentDateTime)) _
        val keeps4 = Seq(
          mkKeep2(Id[Keep](9), uriKifi),
          mkKeep2(Id[Keep](10), uriGoog),
          mkKeep2(Id[Keep](11), uriApple)
        )

        val commander = inject[HelpRankCommander]
        Await.result(commander.processKeepAttribution(u1.id.get, keeps1), 5 seconds)
        Await.result(commander.processKeepAttribution(u2.id.get, keeps2), 5 seconds) // nothing yet

        val (kc0, kc1, kc2, kc3) = db.readWrite { implicit rw =>
          val kifiHitCache = inject[KifiHitCache]
          val origin = "https://www.google.com"
          val kc0 = keepDiscoveryRepo.save(KeepDiscovery(createdAt = currentDateTime, hitUUID = ExternalId[ArticleSearchResult](), numKeepers = 1, keeperId = u1.id.get, keepId = keeps1(0).id.get, uriId = keeps1(0).uriId))
          // u2 -> 42 (u1)
          kifiHitCache.set(KifiHitKey(u2.id.get, keeps1(0).uriId), SanitizedKifiHit(kc0.hitUUID, origin, keeps1(0).url, kc0.uriId, KifiHitContext(false, false, 0, Seq(u1.externalId), Seq.empty, None, 0, 0)))

          val ts = currentDateTime
          val uuid = ExternalId[ArticleSearchResult]()
          val kc1 = keepDiscoveryRepo.save(KeepDiscovery(createdAt = ts, hitUUID = uuid, numKeepers = 2, keeperId = u1.id.get, keepId = keeps1(1).id.get, uriId = keeps1(1).uriId))
          val kc2 = keepDiscoveryRepo.save(KeepDiscovery(createdAt = ts, hitUUID = uuid, numKeepers = 2, keeperId = u2.id.get, keepId = keeps2(0).id.get, uriId = keeps2(0).uriId))
          // u3 -> kifi (u1, u2) [rekeep]
          kifiHitCache.set(KifiHitKey(u3.id.get, keeps1(1).uriId), SanitizedKifiHit(kc1.hitUUID, origin, keeps1(1).url, kc1.uriId, KifiHitContext(false, false, 0, Seq(u1.externalId, u2.externalId), Seq.empty, None, 0, 0)))

          // u3 -> bing (u2) [rekeep]
          val ts3 = currentDateTime
          val uuid3 = ExternalId[ArticleSearchResult]()
          val kc3 = keepDiscoveryRepo.save(KeepDiscovery(createdAt = ts3, hitUUID = uuid3, numKeepers = 1, keeperId = u2.id.get, keepId = keeps2(2).id.get, uriId = keeps2(2).uriId))
          kifiHitCache.set(KifiHitKey(u3.id.get, keeps2(2).uriId), SanitizedKifiHit(kc3.hitUUID, origin, keeps2(2).url, kc3.uriId, KifiHitContext(false, false, 0, Seq(u2.externalId), Seq.empty, None, 0, 0)))
          (kc0, kc1, kc2, kc3)
        }

        Await.result(commander.processKeepAttribution(u3.id.get, keeps3), 5 seconds)

        val kc4 = db.readWrite { implicit rw =>
          val kifiHitCache = inject[KifiHitCache]
          val origin = "https://www.google.com"
          val kc4 = keepDiscoveryRepo.save(KeepDiscovery(createdAt = currentDateTime, hitUUID = ExternalId[ArticleSearchResult](), numKeepers = 1, keeperId = u3.id.get, keepId = keeps3(0).id.get, uriId = keeps3(0).uriId))
          // u4 -> kifi (u3) [rekeep]
          kifiHitCache.set(KifiHitKey(u4.id.get, keeps3(0).uriId), SanitizedKifiHit(kc4.hitUUID, origin, keeps3(0).url, kc4.uriId, KifiHitContext(false, false, 0, Seq(u3.externalId), Seq.empty, None, 0, 0)))
          kc4
        }

        Await.result(commander.processKeepAttribution(u4.id.get, keeps4), 5 seconds)

        db.readWrite { implicit session =>

          val allDiscoveries = keepDiscoveryRepo.all()
          allDiscoveries.size === 5

          val cu0 = keepDiscoveryRepo.getDiscoveriesByUUID(kc0.hitUUID)
          cu0.size === 1
          cu0(0).createdAt === kc0.createdAt
          cu0(0).hitUUID === kc0.hitUUID
          cu0(0).keeperId === u1.id.get
          cu0(0).keepId === keeps1(0).id.get

          val cu1 = keepDiscoveryRepo.getDiscoveriesByUUID(kc1.hitUUID)
          cu1.size === 2

          val c1 = cu1.find(_.keeperId == u1.id.get).get
          c1.createdAt === kc1.createdAt
          c1.createdAt === kc2.createdAt
          c1.hitUUID === kc2.hitUUID

          c1.keeperId === u1.id.get
          c1.keepId === keeps1(1).id.get

          val c2 = cu1.find(_.keeperId == u2.id.get).get
          c2.createdAt === kc1.createdAt
          c2.hitUUID === kc1.hitUUID
          c2.keeperId === u2.id.get
          c2.keepId === keeps2(0).id.get

          val ck1 = keepDiscoveryRepo.getDiscoveriesByKeeper(u1.id.get)
          ck1.size === 2

          val ck2 = keepDiscoveryRepo.getDiscoveriesByKeeper(u2.id.get)
          ck2.size === 2

          val counts1 = keepDiscoveryRepo.getDiscoveryCountsByKeeper(u1.id.get)
          counts1.keySet.size === 2
          counts1.get(keeps1(0).id.get) === Some(1)
          counts1.get(keeps1(1).id.get) === Some(1)
          val uriCounts1 = keepDiscoveryRepo.getUriDiscoveryCountsByKeeper(u1.id.get)
          uriCounts1.size === counts1.size
          uriCounts1.map(_._2).toSeq.sorted === counts1.map(_._2).toSeq.sorted
          uriCounts1.forall {
            case (uriId, count) =>
              val keep = keeps1.find(_.uriId == uriId).get
              counts1.get(keep.id.get).get == count
          } === true

          val counts2 = keepDiscoveryRepo.getDiscoveryCountsByKeeper(u2.id.get)
          counts2.keySet.size === 2
          counts2.get(keeps2(0).id.get) === Some(1)
          counts2.get(keeps2(1).id.get) === None
          counts2.get(keeps2(2).id.get) === Some(1)

          keepDiscoveryRepo.getDiscoveryCountByKeeper(u1.id.get) === ck1.size
          keepDiscoveryRepo.getDiscoveryCountByKeeper(u2.id.get) === ck2.size
          keepDiscoveryRepo.getDiscoveryCountByKeeper(u3.id.get) === keepDiscoveryRepo.getDiscoveriesByKeeper(u3.id.get).length
          keepDiscoveryRepo.getDiscoveryCountByKeeper(u4.id.get) === keepDiscoveryRepo.getDiscoveriesByKeeper(u4.id.get).length

          val cm1 = keepDiscoveryRepo.getDiscoveryCountsByKeepIds(u1.id.get, keeps1.map(_.id.get).toSet)
          cm1.get(keeps1(0).id.get) === Some(1)
          cm1.get(keeps1(1).id.get) === Some(1)

          val cm2 = keepDiscoveryRepo.getDiscoveryCountsByKeepIds(u2.id.get, keeps2.map(_.id.get).toSet)

          val rekeeps = rekeepRepo.all
          rekeeps.size === 4

          val rk1 = rekeeps.find(_.keeperId == u1.id.get).get
          rk1.keeperId === u1.id.get
          rk1.keepId === keeps1(1).id.get
          rk1.srcUserId === u3.id.get
          rk1.srcKeepId === keeps3(0).id.get

          val rk2 = rekeeps.find(_.keeperId == u2.id.get).get
          rk2.keeperId === u2.id.get
          rk2.keepId === keeps2(0).id.get
          rk2.srcUserId === u3.id.get
          rk2.srcKeepId === keeps3(0).id.get

          val rkmap1 = rekeepRepo.getReKeeps(Set(keeps1(1).id.get))
          val rkseq1 = rkmap1(keeps1(1).id.get)
          rkseq1.length === 1
          rkseq1(0).keepId === keeps1(1).id.get
          rkseq1(0).srcUserId === u3.id.get
          rkseq1(0).srcKeepId === keeps3(0).id.get

          val rkmap2 = rekeepRepo.getReKeeps(Set(keeps2(0).id.get, keeps2(2).id.get))
          val rkseq2a = rkmap2(keeps2(0).id.get)
          rkseq2a.length === 1
          rkseq2a(0).keepId === keeps2(0).id.get
          rkseq2a(0).srcUserId === u3.id.get
          rkseq2a(0).srcKeepId === keeps3(0).id.get
          val rkseq2b = rkmap2(keeps2(2).id.get)
          rkseq2b.length === 1
          rkseq2b(0).keepId === keeps2(2).id.get
          rkseq2b(0).srcUserId === u3.id.get
          rkseq2b(0).srcKeepId === keeps3(2).id.get

          val rkmap3 = rekeepRepo.getReKeeps(Set(keeps3(0).id.get))
          val rkseq3 = rkmap3(keeps3(0).id.get)
          rkseq3.length === 1
          rkseq3(0).keepId === keeps3(0).id.get
          rkseq3(0).srcUserId === u4.id.get
          rkseq3(0).srcKeepId === keeps4(0).id.get

          val rkbk1 = rekeepRepo.getReKeepsByKeeper(u1.id.get)
          rkbk1.head === rk1
          rekeepRepo.getAllReKeepsByKeeper(u1.id.get).sortBy(_.createdAt) === rkbk1.sortBy(_.createdAt)

          val rkbk2 = rekeepRepo.getReKeepsByKeeper(u2.id.get)
          rkbk2.head === rk2
          rekeepRepo.getReKeepsByReKeeper(u3.id.get).length === 3
          rekeepRepo.getAllReKeepsByKeeper(u2.id.get).sortBy(_.createdAt) === rkbk2.sortBy(_.createdAt)

          val rkc1 = rekeepRepo.getReKeepCountsByKeeper(u1.id.get)
          rkc1.get(keeps1(0).id.get) === None
          rkc1.get(keeps1(1).id.get) === Some(1)
          val rkc1a = rekeepRepo.getReKeepCountsByKeepIds(u1.id.get, keeps1.map(_.id.get).toSet)
          rkc1a.size === rkc1.size
          keeps1.forall { keep =>
            rkc1.get(keep.id.get) == rkc1a.get(keep.id.get)
          } === true
          val rkc1b = rekeepRepo.getReKeepCountsByKeepIds(u1.id.get, Set(keeps1(1).id.get))
          rkc1b.size === 1
          rkc1b.get(keeps1(1).id.get) === rkc1.get(keeps1(1).id.get)
          val rkc1c = rekeepRepo.getReKeepCountsByKeepIds(u1.id.get, Set(keeps1(0).id.get))
          rkc1c.size === 0

          val uriRKC1 = rekeepRepo.getUriReKeepCountsByKeeper(u1.id.get)
          uriRKC1.size === rkc1.size
          uriRKC1.forall {
            case (uri, count) =>
              val keep = keeps1.find(_.uriId == uri).get
              rkc1.get(keep.id.get).get == count
          } === true

          val rkc2 = rekeepRepo.getReKeepCountsByKeeper(u2.id.get)
          rkc2.get(keeps2(0).id.get) === Some(1)
          rkc2.get(keeps2(1).id.get) === None
          rkc2.get(keeps2(2).id.get) === Some(1)

          rekeepRepo.getReKeepCountByKeeper(u1.id.get) === rkc1.valuesIterator.foldLeft(0) { (a, c) => a + c }
          rekeepRepo.getReKeepCountByKeeper(u2.id.get) === rkc2.valuesIterator.foldLeft(0) { (a, c) => a + c }
          rekeepRepo.getReKeepCountByKeeper(u3.id.get) === 1
          rekeepRepo.getReKeepCountByKeeper(u4.id.get) === 0

          keepDiscoveryRepo.getDiscoveryCountByURI(keeps1(0).uriId) === 1
          keepDiscoveryRepo.getDiscoveryCountByURI(keeps1(1).uriId) === 2
          val kdcURIs1 = keepDiscoveryRepo.getDiscoveryCountsByURIs(keeps1.map(_.uriId).toSet)
          keeps1.forall {
            case k =>
              kdcURIs1.get(k.uriId).get == keepDiscoveryRepo.getDiscoveryCountByURI(k.uriId)
          } === true
          val kdcURIs2 = keepDiscoveryRepo.getDiscoveryCountsByURIs(keeps2.map(_.uriId).toSet)
          keeps2.forall {
            case k =>
              kdcURIs2.get(k.uriId).get == keepDiscoveryRepo.getDiscoveryCountByURI(k.uriId)
          } === true

          rekeepRepo.getReKeepCountByURI(keeps1(0).uriId) === 0
          rekeepRepo.getReKeepCountByURI(keeps1(1).uriId) === 2
          val rkcURIs1 = rekeepRepo.getReKeepCountsByURIs(keeps1.map(_.uriId).toSet)
          rkcURIs1.get(keeps1(1).uriId).get === 2
          keeps1.forall {
            case k =>
              rkcURIs1.get(k.uriId).get == rekeepRepo.getReKeepCountByURI(k.uriId)
          } === true
          val rkcURIs2 = rekeepRepo.getReKeepCountsByURIs(keeps2.map(_.uriId).toSet)
          keeps2.forall {
            case k =>
              rkcURIs2.get(k.uriId).get == rekeepRepo.getReKeepCountByURI(k.uriId)
          } === true

          // rows + counts
          val discoveriesWithCounts1 = keepDiscoveryRepo.getUriDiscoveriesWithCountsByKeeper(u1.id.get)
          discoveriesWithCounts1.length === 2
          discoveriesWithCounts1(0)._1 === keeps1(1).uriId // reverse chronological
          discoveriesWithCounts1(1)._1 === keeps1(0).uriId
          discoveriesWithCounts1.forall {
            case (uriId, _, _, count) =>
              counts1.get(keeps1.find(_.uriId == uriId).get.id.get).get == count
          } === true
          val discoveriesWithCounts2 = keepDiscoveryRepo.getUriDiscoveriesWithCountsByKeeper(u2.id.get)
          discoveriesWithCounts2.length !== keeps2.length // not all have been discovered
          discoveriesWithCounts2.length === 2
          discoveriesWithCounts2(0)._1 === keeps2(2).uriId // reverse chronological
          discoveriesWithCounts2(1)._1 === keeps2(0).uriId

          val rekeepsWithCounts1 = rekeepRepo.getUriReKeepsWithCountsByKeeper(u1.id.get)
          rekeepsWithCounts1.length === 1
          rekeepsWithCounts1.forall {
            case (uriId, _, _, count) =>
              rkc1.get(keeps1.find(_.uriId == uriId).get.id.get).get == count
          } === true
          val rekeepsWithCounts2 = rekeepRepo.getUriReKeepsWithCountsByKeeper(u2.id.get)
          rekeepsWithCounts2.length === 2
          rekeepsWithCounts2.forall {
            case (uriId, _, _, count) =>
              rkc2.get(keeps2.find(_.uriId == uriId).get.id.get).get == count
          } === true

        }

        db.readWrite { implicit rw =>
          (keeps1 ++ keeps2 ++ keeps3 ++ keeps4).foreach { keep =>
            userBookmarkClicksRepo.save(UserBookmarkClicks(userId = keep.userId, uriId = keep.uriId, selfClicks = 0, otherClicks = 0))
          }
        }
        val attrCmdr = inject[AttributionCommander]
        val rkbd1 = attrCmdr.getReKeepsByDegree(u1.id.get, keeps1(1).id.get, 3)
        rkbd1.length === 3
        val (ubd1, kbd1) = rkbd1.unzip
        ubd1(0) === Seq(u1.id.get)
        ubd1(1) === Seq(u3.id.get)
        ubd1(2) === Seq(u4.id.get)
        kbd1(0) === Seq(keeps1(1).id.get)
        kbd1(1) === Seq(keeps3(0).id.get)
        kbd1(2) === Seq(keeps4(0).id.get)

        val bc1 = Await.result(attrCmdr.updateUserReKeepStats(u1.id.get), Duration.Inf)
        bc1.nonEmpty === true
        bc1.length === 1
        bc1(0).rekeepCount === 1
        bc1(0).rekeepTotalCount === 2

        val bc3 = Await.result(attrCmdr.updateUserReKeepStats(u3.id.get), Duration.Inf)
        bc3(0).rekeepCount === 1
        bc3(0).rekeepTotalCount === 1

        val users = Seq(u1, u2, u3, u4)
        val allStats = Await.result(attrCmdr.updateUsersReKeepStats(users.map(_.id.get)), Duration.Inf)
        allStats.foreach { s => println(s"(len=${s.length}); ${s.mkString(",")})") }
        allStats(0).length === bc1.length
        allStats(0)(0).rekeepCount === bc1(0).rekeepCount
        allStats(0)(0).rekeepTotalCount === bc1(0).rekeepTotalCount

        allStats(2).length === bc3.length
        allStats(2)(0).rekeepCount === bc3(0).rekeepCount
        allStats(2)(0).rekeepTotalCount === bc3(0).rekeepTotalCount

        val (rkc1, rktc1) = db.readOnlyMaster { implicit ro => userBookmarkClicksRepo.getReKeepCounts(u1.id.get) }
        bc1.foldLeft(0) { (a, c) => a + c.rekeepCount } === rkc1
        bc1.foldLeft(0) { (a, c) => a + c.rekeepTotalCount } === rktc1

        val (rkc3, rktc3) = db.readOnlyMaster { implicit ro => userBookmarkClicksRepo.getReKeepCounts(u3.id.get) }
        bc3.foldLeft(0) { (a, c) => a + c.rekeepCount } === rkc3
        bc3.foldLeft(0) { (a, c) => a + c.rekeepTotalCount } === rktc3

      }
    }

    // (ray) test failing in jenkins -- temporarily comment out to unblock build
    "tracking messages & rekeeps" in {
      val attrInfo = new collection.mutable.HashMap[Id[NormalizedURI], Seq[Id[User]]]()
      withDb((modules ++ Seq(FakeElizaServiceClientModule(attributionInfo = attrInfo))): _*) { implicit injector =>
        try {
          val shoebox = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
          val ts = currentDateTime
          val u1 = User(id = Some(Id[User](1)), createdAt = ts, updatedAt = ts, firstName = "Shanee", lastName = "Smith")
          val u2 = User(id = Some(Id[User](2)), createdAt = ts, updatedAt = ts, firstName = "Foo", lastName = "Bar")
          val savedUsers = shoebox.saveUsers(u1, u2)

          val uri42 = mkURI(Id[NormalizedURI](1), "http://42go.com", SequenceNumber[NormalizedURI](1))
          val uriKifi = mkURI(Id[NormalizedURI](2), "http://kifi.com", SequenceNumber[NormalizedURI](2))
          val uriGoog = mkURI(Id[NormalizedURI](3), "http://google.com", SequenceNumber[NormalizedURI](3))
          val uriBing = mkURI(Id[NormalizedURI](4), "http://bing.com", SequenceNumber[NormalizedURI](4))
          val savedURIs = shoebox.saveURIs(uri42, uriKifi, uriGoog, uriBing)

          val mkKeep1 = mkKeep(u1.id.get, Some(currentDateTime)) _
          val keeps1 = Seq(
            mkKeep1(Id[Keep](1), uri42),
            mkKeep1(Id[Keep](2), uriKifi)
          )
          val mkKeep2 = mkKeep(u2.id.get, Some(currentDateTime)) _
          val keeps2 = Seq(
            mkKeep2(Id[Keep](3), uriKifi),
            mkKeep2(Id[Keep](4), uriGoog),
            mkKeep2(Id[Keep](5), uriBing)
          )
          val savedKeeps = shoebox.saveBookmarks(keeps1 ++ keeps2: _*)

          attrInfo += (keeps1(1).uriId -> Seq(u1.id.get)) // u1 - chat(kifi) - u2 (rekeep)

          val commander = inject[HelpRankCommander]
          Await.result(commander.processKeepAttribution(u2.id.get, keeps2), Duration.Inf)

          val clicks1 = db.readOnlyMaster { implicit rw =>
            keepDiscoveryRepo.getByKeepId(keeps1(1).id.get)
          }
          clicks1.size === 1
          clicks1.headOption.exists { click =>
            click.keeperId == u1.id.get && click.keepId == keeps1(1).id.get
          } === true
          val rekeeps1 = db.readOnlyMaster { implicit ro =>
            rekeepRepo.getAllReKeepsByKeeper(u1.id.get)
          }
          rekeeps1.length === 1
          val rk = rekeeps1(0)
          rk.keeperId === u1.id.get
          rk.keepId === keeps1(1).id.get
          rk.srcUserId === u2.id.get
        } catch {
          case t: Throwable =>
            println(s"Caught Exception $t; cause=${t.getCause}; \n\t${t.getStackTraceString}")
            throw t
        }
      }
    }
  }

}
