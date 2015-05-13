package com.keepit.commanders

import java.util.concurrent.atomic.AtomicInteger

import com.keepit.commander.{ AttributionCommander, HelpRankCommander }
import com.keepit.common.db.{ ExternalId, Id, SequenceNumber }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.time._
import com.keepit.heimdal.{ SearchHitReportKey, SearchHitReportCache, SearchHitReport }
import com.keepit.model._
import com.keepit.search.ArticleSearchResult
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test._
import net.codingwell.scalaguice.ScalaModule
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions

import scala.concurrent.Await
import scala.concurrent.duration._

class HelpRankCommanderTest extends Specification with HeimdalTestInjector with NoTimeConversions {

  val keepIdCounter = new AtomicInteger(0)
  def mkKeep(userId: Id[User], ts: DateTime = currentDateTime, idOpt: Option[Id[Keep]] = None, libraryId: Option[Id[Library]] = None)(uri: NormalizedURI): Keep = {
    val id = idOpt getOrElse Id[Keep](keepIdCounter.incrementAndGet)
    Keep(id = Some(id), createdAt = ts, updatedAt = ts, uriId = uri.id.get, url = uri.url, urlId = Id[URL](uri.id.get.id), visibility = Keep.isPrivateToVisibility(false), userId = userId, source = KeepSource.keeper, libraryId = libraryId, inDisjointLib = true)
  }

  val uriIdCounter = new AtomicInteger(0)
  val uriSeqCounter = new AtomicInteger(0)
  def mkURI(url: String, idOpt: Option[Id[NormalizedURI]] = None, seqOpt: Option[SequenceNumber[NormalizedURI]] = None, tsOpt: Option[DateTime] = None): NormalizedURI = {
    val id = idOpt getOrElse Id[NormalizedURI](uriIdCounter.incrementAndGet)
    val seq = seqOpt getOrElse SequenceNumber[NormalizedURI](uriSeqCounter.incrementAndGet)
    val ts = tsOpt getOrElse currentDateTime
    NormalizedURI(id = Some(id), createdAt = ts, updatedAt = ts, url = url, urlHash = NormalizedURI.hashUrl(url), seq = seq)
  }

  val FORTYTWO = "42"
  val KIFI = "KIFI"
  val GOOG = "GOOG"
  val BING = "BING"
  val LNKD = "LNKD"
  val APPL = "APPL"

  def modules: Seq[ScalaModule] = Seq(
    FakeHttpClientModule(),
    FakeShoeboxServiceModule()
  )

  "HelpRankCommander" should {

    "track discoveries & rekeeps" in {
      withDb(modules: _*) { implicit injector =>
        val ts = currentDateTime
        val u1 = User(id = Some(Id[User](1)), createdAt = ts, updatedAt = ts, firstName = "Shanee", lastName = "Smith", username = Username("test"), normalizedUsername = "test")
        val u2 = User(id = Some(Id[User](2)), createdAt = ts, updatedAt = ts, firstName = "Foo", lastName = "Bar", username = Username("test"), normalizedUsername = "test")
        val u3 = User(id = Some(Id[User](3)), createdAt = ts, updatedAt = ts, firstName = "Ping", lastName = "Pong", username = Username("test"), normalizedUsername = "test")
        val u4 = User(id = Some(Id[User](4)), createdAt = ts, updatedAt = ts, firstName = "Ro", lastName = "Bot", username = Username("test"), normalizedUsername = "test")

        val uri42 = mkURI("http://42go.com")
        val uriKifi = mkURI("http://kifi.com")
        val uriGoog = mkURI("http://google.com")
        val uriBing = mkURI("http://bing.com")
        val uriLnkd = mkURI("http://linkedin.com")
        val uriAppl = mkURI("http://apple.com")
        val uris = Map(
          FORTYTWO -> uri42,
          KIFI -> uriKifi,
          GOOG -> uriGoog,
          BING -> uriBing,
          LNKD -> uriLnkd,
          APPL -> uriAppl
        )

        val mkKeep1 = mkKeep(u1.id.get, currentDateTime) _
        val k1 = Map(
          FORTYTWO -> mkKeep1(uri42),
          KIFI -> mkKeep1(uriKifi)
        )
        val keeps1 = k1.values.toSeq

        val mkKeep2 = mkKeep(u2.id.get, currentDateTime) _
        val k2 = Map(
          KIFI -> mkKeep2(uriKifi),
          GOOG -> mkKeep2(uriGoog),
          BING -> mkKeep2(uriBing)
        )
        val keeps2 = k2.values.toSeq

        val mkKeep3 = mkKeep(u3.id.get, currentDateTime) _
        val k3 = Map(
          KIFI -> mkKeep2(uriKifi),
          LNKD -> mkKeep2(uriLnkd),
          BING -> mkKeep2(uriBing)
        )
        val keeps3 = k3.values.toSeq

        val mkKeep4 = mkKeep(u3.id.get, currentDateTime) _
        val k4 = Map(
          KIFI -> mkKeep2(uriKifi),
          GOOG -> mkKeep2(uriGoog),
          APPL -> mkKeep2(uriAppl)
        )
        val keeps4 = k4.values.toSeq

        val keeps = Map(u1 -> keeps1, u2 -> keeps2, u3 -> keeps3, u4 -> keeps4)

        val commander = inject[HelpRankCommander]
        Await.result(commander.processKeepAttribution(u1.id.get, keeps1), Duration.Inf)
        keeps1.forall { k =>
          db.readOnlyMaster { implicit s => keepDiscoveryRepo.getDiscoveryCountByURI(k.uriId) } == 0
        } === true
        db.readOnlyMaster { implicit s =>
          keepDiscoveryRepo.getUriDiscoveriesWithCountsByKeeper(u1.id.get) forall { uriDisc =>
            uriDisc._4 == 0
          }
        }

        val kc0 = db.readWrite { implicit rw =>
          val kifiHitCache = inject[SearchHitReportCache]
          val origin = "https://www.google.com"
          val kc0 = keepDiscoveryRepo.save(KeepDiscovery(createdAt = currentDateTime, hitUUID = ExternalId[ArticleSearchResult](), numKeepers = 1, keeperId = u1.id.get, keepId = k1(FORTYTWO).id.get, uriId = k1(FORTYTWO).uriId))
          // u2 -> 42 (u1) [discovery]
          kifiHitCache.set(SearchHitReportKey(u2.id.get, k1(FORTYTWO).uriId), SearchHitReport(u2.id.get, kc0.uriId, false, Seq(u1.externalId), origin, kc0.hitUUID))
          kc0
        }

        Await.result(commander.processKeepAttribution(u2.id.get, keeps2), Duration.Inf)
        db.readOnlyMaster { implicit s => keepDiscoveryRepo.getDiscoveryCountByURI(k1(FORTYTWO).uriId) } === 1
        db.readOnlyMaster { implicit s =>
          keepDiscoveryRepo.getUriDiscoveriesWithCountsByKeeper(u1.id.get).filter(_._1 == k1(FORTYTWO).uriId) map {
            case (uriId, keepId, userId, count) =>
              count === 1
          }
        }

        val (kc1, kc2, kc3) = db.readWrite { implicit rw =>
          val kifiHitCache = inject[SearchHitReportCache]
          val origin = "https://www.google.com"

          val ts = currentDateTime
          val uuid = ExternalId[ArticleSearchResult]()
          val kc1 = keepDiscoveryRepo.save(KeepDiscovery(createdAt = ts, hitUUID = uuid, numKeepers = 2, keeperId = u1.id.get, keepId = k1(KIFI).id.get, uriId = k1(KIFI).uriId))
          val kc2 = keepDiscoveryRepo.save(KeepDiscovery(createdAt = ts, hitUUID = uuid, numKeepers = 2, keeperId = u2.id.get, keepId = k2(KIFI).id.get, uriId = k2(KIFI).uriId))
          // u3 -> kifi (u1, u2) [rekeep]
          kifiHitCache.set(SearchHitReportKey(u3.id.get, k1(KIFI).uriId), SearchHitReport(u3.id.get, kc1.uriId, false, Seq(u1.externalId), origin, kc1.hitUUID))

          // u3 -> bing (u2) [rekeep]
          val ts3 = currentDateTime
          val uuid3 = ExternalId[ArticleSearchResult]()
          val kc3 = keepDiscoveryRepo.save(KeepDiscovery(createdAt = ts3, hitUUID = uuid3, numKeepers = 1, keeperId = u2.id.get, keepId = k2(BING).id.get, uriId = k2(BING).uriId))
          kifiHitCache.set(SearchHitReportKey(u3.id.get, k2(BING).uriId), SearchHitReport(u3.id.get, kc3.uriId, false, Seq(u2.externalId), origin, kc3.hitUUID))
          (kc1, kc2, kc3)
        }
        Await.result(commander.processKeepAttribution(u3.id.get, keeps3), Duration.Inf)
        db.readOnlyMaster { implicit s =>
          keepDiscoveryRepo.getDiscoveryCountByURI(uri42.id.get) === 1
          keepDiscoveryRepo.getDiscoveryCountByURI(uriKifi.id.get) === 1
          keepDiscoveryRepo.getDiscoveryCountByURI(uriBing.id.get) === 1

          rekeepRepo.getReKeepCountByURI(uriKifi.id.get) === 1
          rekeepRepo.getReKeepCountByURI(uriBing.id.get) === 1

          val disc = keepDiscoveryRepo.getUriDiscoveriesWithCountsByKeeper(u1.id.get)
          disc.length === 2
          disc.filter(_._1 == uri42.id.get).head._4 === 1
          disc.filter(_._1 == uriKifi.id.get).head._4 === 1

          val rk = rekeepRepo.getUriReKeepsWithCountsByKeeper(u1.id.get)
          rk.length === 1
          rk.filter(_._1 == uriKifi.id.get).head._4 === 1
        }

        val kc4 = db.readWrite { implicit rw =>
          val kifiHitCache = inject[SearchHitReportCache]
          val origin = "https://www.google.com"
          val kc4 = keepDiscoveryRepo.save(KeepDiscovery(createdAt = currentDateTime, hitUUID = ExternalId[ArticleSearchResult](), numKeepers = 1, keeperId = u3.id.get, keepId = k3(KIFI).id.get, uriId = k3(KIFI).uriId))
          // u4 -> kifi (u3) [rekeep]
          kifiHitCache.set(SearchHitReportKey(u4.id.get, k3(KIFI).uriId), SearchHitReport(u4.id.get, kc4.uriId, false, Seq(u2.externalId), origin, kc4.hitUUID))
          kc4
        }
        Await.result(commander.processKeepAttribution(u4.id.get, keeps4), Duration.Inf)
        db.readOnlyMaster { implicit s => keepDiscoveryRepo.getDiscoveryCountByURI(uriKifi.id.get) } === 2
        db.readOnlyMaster { implicit s => rekeepRepo.getReKeepCountByURI(uriKifi.id.get) } === 2

        db.readOnlyMaster { implicit session =>
          val allDiscoveries = keepDiscoveryRepo.all()
          allDiscoveries.size === 5

          val cu0 = keepDiscoveryRepo.getDiscoveriesByUUID(kc0.hitUUID)
          cu0.size === 1
          cu0(0).createdAt === kc0.createdAt
          cu0(0).hitUUID === kc0.hitUUID
          cu0(0).keeperId === u1.id.get
          cu0(0).keepId === k1(FORTYTWO).id.get

          val cu1 = keepDiscoveryRepo.getDiscoveriesByUUID(kc1.hitUUID)
          cu1.size === 2

          val c1 = cu1.find(_.keeperId == u1.id.get).get
          c1.createdAt === kc1.createdAt
          c1.createdAt === kc2.createdAt
          c1.hitUUID === kc2.hitUUID

          c1.keeperId === u1.id.get
          c1.keepId === k1(KIFI).id.get

          val c2 = cu1.find(_.keeperId == u2.id.get).get
          c2.createdAt === kc1.createdAt
          c2.hitUUID === kc1.hitUUID
          c2.keeperId === u2.id.get
          c2.keepId === k2(KIFI).id.get

          val ck1 = keepDiscoveryRepo.getDiscoveriesByKeeper(u1.id.get)
          ck1.size === 2

          val ck2 = keepDiscoveryRepo.getDiscoveriesByKeeper(u2.id.get)
          ck2.size === 2

          val counts1 = keepDiscoveryRepo.getDiscoveryCountsByKeeper(u1.id.get)
          counts1.keySet.size === 2
          counts1.get(k1(FORTYTWO).id.get) === Some(1)
          counts1.get(k1(KIFI).id.get) === Some(1)
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
          counts2.get(k2(KIFI).id.get) === Some(1)
          counts2.get(k2(GOOG).id.get) === None
          counts2.get(k2(BING).id.get) === Some(1)

          keepDiscoveryRepo.getDiscoveryCountByKeeper(u1.id.get) === ck1.size
          keepDiscoveryRepo.getDiscoveryCountByKeeper(u2.id.get) === ck2.size
          keepDiscoveryRepo.getDiscoveryCountByKeeper(u3.id.get) === keepDiscoveryRepo.getDiscoveriesByKeeper(u3.id.get).length
          keepDiscoveryRepo.getDiscoveryCountByKeeper(u4.id.get) === keepDiscoveryRepo.getDiscoveriesByKeeper(u4.id.get).length

          val cm1 = keepDiscoveryRepo.getDiscoveryCountsByKeepIds(u1.id.get, keeps1.map(_.id.get).toSet)
          cm1.get(k1(FORTYTWO).id.get) === Some(1)
          cm1.get(k1(KIFI).id.get) === Some(1)

          val cm2 = keepDiscoveryRepo.getDiscoveryCountsByKeepIds(u2.id.get, keeps2.map(_.id.get).toSet)

          keeps.values.forall { keeps =>
            val uriDiscCounts = keepDiscoveryRepo.getDiscoveryCountsByURIs(keeps.map(_.uriId).toSet)
            uriDiscCounts.toSeq.forall {
              case (uriId, count) =>
                keepDiscoveryRepo.getDiscoveryCountByURI(uriId) == count
            }
          } === true

          val rekeeps = rekeepRepo.all
          rekeeps.size === 4

          val rk1 = rekeeps.find(_.keeperId == u1.id.get).get
          rk1.keeperId === u1.id.get
          rk1.keepId === k1(KIFI).id.get
          rk1.srcUserId === u3.id.get
          rk1.srcKeepId === k3(KIFI).id.get

          val rk2 = rekeeps.find(_.keeperId == u2.id.get).get
          rk2.keeperId === u2.id.get
          rk2.keepId === k2(KIFI).id.get
          rk2.srcUserId === u3.id.get
          rk2.srcKeepId === k3(KIFI).id.get

          val rkmap1 = rekeepRepo.getReKeeps(Set(k1(KIFI).id.get))
          val rkseq1 = rkmap1(k1(KIFI).id.get)
          rkseq1.length === 1
          rkseq1(0).keepId === k1(KIFI).id.get
          rkseq1(0).srcUserId === u3.id.get
          rkseq1(0).srcKeepId === k3(KIFI).id.get

          val rkmap2 = rekeepRepo.getReKeeps(Set(k2(KIFI).id.get, k2(BING).id.get))
          val rkseq2a = rkmap2(k2(KIFI).id.get)
          rkseq2a.length === 1
          rkseq2a(0).keepId === k2(KIFI).id.get
          rkseq2a(0).srcUserId === u3.id.get
          rkseq2a(0).srcKeepId === k3(KIFI).id.get
          val rkseq2b = rkmap2(k2(BING).id.get)
          rkseq2b.length === 1
          rkseq2b(0).keepId === k2(BING).id.get
          rkseq2b(0).srcUserId === u3.id.get
          rkseq2b(0).srcKeepId === k3(BING).id.get

          val rkmap3 = rekeepRepo.getReKeeps(Set(k3(KIFI).id.get))
          val rkseq3 = rkmap3(k3(KIFI).id.get)
          rkseq3.length === 1
          rkseq3(0).keepId === k3(KIFI).id.get
          rkseq3(0).srcUserId === u4.id.get
          rkseq3(0).srcKeepId === k4(KIFI).id.get

          val rkbk1 = rekeepRepo.getReKeepsByKeeper(u1.id.get)
          rkbk1.head === rk1
          rekeepRepo.getAllReKeepsByKeeper(u1.id.get).sortBy(_.createdAt) === rkbk1.sortBy(_.createdAt)

          val rkbk2 = rekeepRepo.getReKeepsByKeeper(u2.id.get)
          rkbk2.head === rk2
          rekeepRepo.getReKeepsByReKeeper(u3.id.get).length === 3
          rekeepRepo.getAllReKeepsByKeeper(u2.id.get).sortBy(_.createdAt) === rkbk2.sortBy(_.createdAt)

          val rkc1 = rekeepRepo.getReKeepCountsByKeeper(u1.id.get)
          rkc1.get(k1(FORTYTWO).id.get) === None
          rkc1.get(k1(KIFI).id.get) === Some(1)
          val rkc1a = rekeepRepo.getReKeepCountsByKeepIds(u1.id.get, keeps1.map(_.id.get).toSet)
          rkc1a.size === rkc1.size
          keeps1.forall { keep =>
            rkc1.get(keep.id.get) == rkc1a.get(keep.id.get)
          } === true
          val rkc1b = rekeepRepo.getReKeepCountsByKeepIds(u1.id.get, Set(k1(KIFI).id.get))
          rkc1b.size === 1
          rkc1b.get(k1(KIFI).id.get) === rkc1.get(k1(KIFI).id.get)
          val rkc1c = rekeepRepo.getReKeepCountsByKeepIds(u1.id.get, Set(k1(FORTYTWO).id.get))
          rkc1c.size === 0

          keeps.values forall { keeps =>
            val uriDiscCounts = rekeepRepo.getReKeepCountsByURIs(keeps.map(_.uriId).toSet)
            uriDiscCounts.toSeq.forall {
              case (uriId, count) =>
                rekeepRepo.getReKeepCountByURI(uriId) == count
            } === true
          }

          val uriRKC1 = rekeepRepo.getUriReKeepCountsByKeeper(u1.id.get)
          uriRKC1.size === rkc1.size
          uriRKC1.forall {
            case (uri, count) =>
              val keep = keeps1.find(_.uriId == uri).get
              rkc1.get(keep.id.get).get == count
          } === true

          val rkc2 = rekeepRepo.getReKeepCountsByKeeper(u2.id.get)
          rkc2.get(k2(KIFI).id.get) === Some(1)
          rkc2.get(k2(GOOG).id.get) === None
          rkc2.get(k2(BING).id.get) === Some(1)

          rekeepRepo.getReKeepCountByKeeper(u1.id.get) === rkc1.valuesIterator.foldLeft(0) { (a, c) => a + c }
          rekeepRepo.getReKeepCountByKeeper(u2.id.get) === rkc2.valuesIterator.foldLeft(0) { (a, c) => a + c }
          rekeepRepo.getReKeepCountByKeeper(u3.id.get) === 1
          rekeepRepo.getReKeepCountByKeeper(u4.id.get) === 0

          keepDiscoveryRepo.getDiscoveryCountByURI(k1(FORTYTWO).uriId) === 1
          keepDiscoveryRepo.getDiscoveryCountByURI(k1(KIFI).uriId) === 2
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

          rekeepRepo.getReKeepCountByURI(k1(FORTYTWO).uriId) === 0
          rekeepRepo.getReKeepCountByURI(k1(KIFI).uriId) === 2
          val rkcURIs1 = rekeepRepo.getReKeepCountsByURIs(keeps1.map(_.uriId).toSet)
          rkcURIs1.get(k1(KIFI).uriId).get === 2
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
          discoveriesWithCounts1(0)._1 === k1(KIFI).uriId // reverse chronological
          discoveriesWithCounts1(1)._1 === k1(FORTYTWO).uriId
          discoveriesWithCounts1.forall {
            case (uriId, _, _, count) =>
              counts1.get(keeps1.find(_.uriId == uriId).get.id.get).get == count
          } === true
          val discoveriesWithCounts2 = keepDiscoveryRepo.getUriDiscoveriesWithCountsByKeeper(u2.id.get)
          discoveriesWithCounts2.length !== keeps2.length // not all have been discovered
          discoveriesWithCounts2.length === 2
          discoveriesWithCounts2(0)._1 === k2(BING).uriId // reverse chronological
          discoveriesWithCounts2(1)._1 === k2(KIFI).uriId

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
          (keeps1 ++ keeps2 ++ keeps3 ++ keeps4).groupBy(k => (k.userId, k.uriId)).keySet.foreach { case (userId, uriId) =>
            userBookmarkClicksRepo.save(UserBookmarkClicks(userId = userId, uriId = uriId, selfClicks = 0, otherClicks = 0))
          }
        }
        val attrCmdr = inject[AttributionCommander]
        val rkbd1 = attrCmdr.getReKeepsByDegree(u1.id.get, k1(KIFI).id.get, 3)
        rkbd1.length === 3
        val (ubd1, kbd1) = rkbd1.unzip
        ubd1(0) === Seq(u1.id.get)
        ubd1(1) === Seq(u3.id.get)
        ubd1(2) === Seq(u4.id.get)
        kbd1(0) === Seq(k1(KIFI).id.get)
        kbd1(1) === Seq(k3(KIFI).id.get)
        kbd1(2) === Seq(k4(KIFI).id.get)

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

  }

}
