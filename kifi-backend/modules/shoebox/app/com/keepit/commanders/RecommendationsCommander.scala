package com.keepit.commanders

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.model.{
  User,
  NormalizedURI,
  UriRecommendationFeedback,
  NormalizedURIRepo,
  UriRecommendationScores,
  NormalizedURIStates,
  URISummary,
  KeepRepo,
  KeepStates,
  Keep,
  LibraryRepo,
  Library,
  ExperimentType,
  UserRepo
}
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.curator.CuratorServiceClient
import com.keepit.curator.model.{
  RecoInfo,
  RecommendationClientType,
  FullRecoInfo,
  UriRecoItemInfo,
  RecoMetaData,
  SeedAttribution,
  RecoAttributionInfo,
  RecoAttributionKind,
  RecoKind,
  LibRecoItemInfo,
  RecoLibraryInfo
}
import com.keepit.common.db.slick.Database
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.domain.DomainToNameMapper

import com.google.inject.Inject
import com.keepit.normalizer.NormalizedURIInterner

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsNull, Json }

import scala.concurrent.Future
import scala.util.Random

class RecommendationsCommander @Inject() (
    curator: CuratorServiceClient,
    db: Database,
    nUriRepo: NormalizedURIRepo,
    libRepo: LibraryRepo,
    userRepo: UserRepo,
    libCommander: LibraryCommander,
    uriSummaryCommander: URISummaryCommander,
    basicUserRepo: BasicUserRepo,
    keepRepo: KeepRepo,
    implicit val publicIdConfig: PublicIdConfiguration,
    userExperimentCommander: LocalUserExperimentCommander) {

  def adHocRecos(userId: Id[User], howManyMax: Int, scoreCoefficientsUpdate: UriRecommendationScores): Future[Seq[KeepInfo]] = {
    curator.adHocRecos(userId, howManyMax, scoreCoefficientsUpdate).flatMap { recos =>
      val recosWithUris: Seq[(RecoInfo, NormalizedURI)] = db.readOnlyReplica { implicit session =>
        recos.map { reco => (reco, nUriRepo.get(reco.uriId)) }
      }.filter(_._2.state == NormalizedURIStates.SCRAPED)

      Future.sequence(recosWithUris.map {
        case (reco, nUri) =>
          uriSummaryCommander.getDefaultURISummary(nUri, waiting = false).map { uriSummary =>
            val extraInfo = reco.attribution.map { attr =>
              attr.topic.map(_.topicName).map { topicName =>
                s"[$topicName;${reco.explain.getOrElse("")}]"
              } getOrElse {
                s"[${reco.explain.getOrElse("")}]"
              }
            } getOrElse ""
            val augmentedDescription = uriSummary.description.map { desc =>
              extraInfo + desc
            } getOrElse {
              extraInfo
            }
            KeepInfo(
              title = nUri.title,
              url = nUri.url,
              isPrivate = false,
              summary = Some(uriSummary.copy(description = Some(augmentedDescription))),
              others = reco.attribution.get.user.map(_.others),
              keepers = db.readOnlyReplica { implicit session => reco.attribution.get.user.map(_.friends.map(basicUserRepo.load)) }
            )
          }
      })

    }
  }

  def updateUriRecommendationFeedback(userId: Id[User], extId: ExternalId[NormalizedURI], feedback: UriRecommendationFeedback): Future[Boolean] = {
    val uriOpt = db.readOnlyMaster { implicit s =>
      nUriRepo.getOpt(extId)
    }
    uriOpt match {
      case Some(uri) => curator.updateUriRecommendationFeedback(userId, uri.id.get, feedback)
      case None => Future.successful(false)
    }
  }

  private def constructRecoItemInfo(nUri: NormalizedURI, uriSummary: URISummary, reco: RecoInfo): UriRecoItemInfo = {
    val libraries: Map[Id[User], Id[Library]] = reco.attribution.flatMap { attr => attr.user.flatMap(_.friendsLib) }.getOrElse(Map.empty)
    val keeperIds: Seq[Id[User]] = reco.attribution.flatMap { attr => attr.user.map(_.friends) }.getOrElse(Seq.empty)

    val libInfos = libraries.toSeq.map {
      case (ownerId, libraryId) =>
        val (lib, owner) = db.readOnlyReplica { implicit session =>
          libRepo.get(libraryId) -> basicUserRepo.load(ownerId)
        }

        RecoLibraryInfo(
          owner = owner,
          id = Library.publicId(libraryId),
          name = lib.name,
          path = Library.formatLibraryPath(owner.username, owner.externalId, lib.slug)
        )
    }

    UriRecoItemInfo(
      id = nUri.externalId,
      title = nUri.title,
      url = nUri.url,
      keepers = db.readOnlyReplica { implicit session => keeperIds.toSet.map(basicUserRepo.load).toSeq },
      libraries = libInfos,
      others = reco.attribution.map { attr =>
        attr.user.map(_.others)
      }.flatten.getOrElse(0),
      siteName = DomainToNameMapper.getNameFromUrl(nUri.url),
      summary = uriSummary
    )
  }

  private def contstructAttributionInfos(attr: SeedAttribution): Seq[RecoAttributionInfo] = {
    val libraryAttrInfos = attr.library.map { libAttrs =>
      libAttrs.libraries.map { libId =>
        val (lib, owner): (Library, User) = db.readOnlyReplica { implicit session =>
          val lib = libRepo.get(libId)
          val owner = userRepo.get(lib.ownerId)
          (lib, owner)
        }
        RecoAttributionInfo(
          kind = RecoAttributionKind.Library,
          name = Some(lib.name),
          url = Some(Library.formatLibraryPath(owner.username, owner.externalId, lib.slug)),
          when = None
        )
      }
    } getOrElse Seq.empty

    val keepAttrInfos = attr.keep.map { keepAttr =>
      keepAttr.keeps.map { keepId =>
        db.readOnlyReplica { implicit session => keepRepo.get(keepId) }
      } filter { keep =>
        keep.state == KeepStates.ACTIVE
      } map { keep =>
        RecoAttributionInfo(
          kind = RecoAttributionKind.Keep,
          name = keep.title,
          url = Some(keep.url),
          when = Some(keep.createdAt)
        )
      }
    } getOrElse Seq.empty

    val topicAttrInfos = attr.topic.map { topicAttr =>
      Seq(RecoAttributionInfo(
        kind = RecoAttributionKind.Topic,
        name = Some(topicAttr.topicName),
        url = None,
        when = None
      ))
    } getOrElse Seq.empty

    libraryAttrInfos ++ keepAttrInfos ++ topicAttrInfos
  }

  def topRecos(userId: Id[User], clientType: RecommendationClientType, more: Boolean, recencyWeight: Float): Future[Seq[FullRecoInfo]] = {
    curator.topRecos(userId, clientType, more, recencyWeight).flatMap { recos =>
      val recosWithUris: Seq[(RecoInfo, NormalizedURI)] = db.readOnlyReplica { implicit session =>
        recos.map { reco => (reco, nUriRepo.get(reco.uriId)) }
      }
      Future.sequence(recosWithUris.map {
        case (reco, nUri) => uriSummaryCommander.getDefaultURISummary(nUri, waiting = false).map { uriSummary =>
          val itemInfo = constructRecoItemInfo(nUri, uriSummary, reco)
          val attributionInfo = contstructAttributionInfos(reco.attribution.get)
          FullRecoInfo(
            kind = RecoKind.Keep,
            metaData = Some(RecoMetaData(attributionInfo)),
            itemInfo = itemInfo,
            explain = reco.explain
          )
        }
      })
    }
  }

  def topPublicRecos(userId: Id[User]): Future[Seq[FullRecoInfo]] = {
    val magicRecosUriIds = Seq(1687897L, 2072909L, 2316003L, 2316459L, 2316465L, 2316466L, 2316474L, 2316475L, 2316476L, 2316477L, 2316479L, 2316484L, 2316491L, 2316492L, 2316495L, 2316499L, 2316504L, 2316510L, 2316514L, 2316522L, 2316530L, 2316532L, 2316535L, 2316537L, 2316538L, 2316540L, 2316544L, 2316546L, 2316549L, 2316561L, 2316562L, 2316567L, 2316569L, 2316585L, 2316590L, 2316591L, 2316593L, 2316597L, 2316598L, 2316599L, 2316606L, 2316608L, 2316612L, 2316613L, 2316617L, 2435549L, 2435568L, 2435571L, 2435574L, 2435577L, 2435881L, 2435931L, 2435932L, 2435947L, 2435951L, 2435954L, 2435955L, 2435956L, 2436003L, 2436004L, 2436005L, 2436008L, 2436011L, 2436014L, 2436028L, 2436031L, 2436060L, 2436079L, 2436081L, 2436138L, 16781L, 709974L, 874365L, 1038512L, 1085215L, 1241347L, 1241865L, 1289453L, 1383308L, 1386219L, 1431461L, 1431613L, 1453663L, 1463984L, 1676781L, 1837767L, 1886885L, 1930523L, 1938462L, 1976452L, 2120762L, 2171905L, 2316993L, 2462278L, 2496195L, 2664090L, 2664135L, 2664137L, 2664139L, 2664142L, 2664145L, 2664146L, 2664150L, 2664151L, 2664154L, 2664157L, 2664161L, 2664163L, 2664165L, 2664167L, 2664169L, 2664190L, 2664194L, 2664197L, 2664201L, 2664202L, 2664207L, 2664208L, 2664209L, 2664214L, 2664216L, 2664217L, 2664218L, 2664219L, 2664225L, 2664226L, 2664228L, 2664229L, 2664235L, 2664237L, 2664238L, 2664243L, 2664246L, 2664248L, 2664249L, 2664251L, 2664252L, 2664255L, 2664259L, 2664264L, 2664267L, 2664270L, 2664275L, 2664277L, 2664278L, 2664280L, 2664282L, 2664285L, 2664286L, 2664290L, 2664296L, 2664302L, 2664304L, 2664306L, 2664307L, 2664308L, 2664310L, 2664312L, 2664313L, 2664314L, 2794827L, 11943L, 14589L, 513686L, 881438L, 1019625L, 1038967L, 1070661L, 1199035L, 1210810L, 1356074L, 1428200L, 1464360L, 1470691L, 1999936L, 2258123L, 2412276L, 2635531L, 2810053L, 2810068L, 2810072L, 2810085L, 2810088L, 1128559L, 1130168L, 1132218L, 1132314L, 1133375L, 1133578L, 1133619L, 1134292L, 1211098L, 1269096L, 1310115L, 1334927L, 1414271L, 1466180L, 2204671L, 2298736L, 2428034L, 2796963L, 2810202L, 2810239L, 406487L, 454681L, 986235L, 2223364L, 2223370L, 2252676L, 2299917L, 2304252L, 2490162L, 2532632L, 2537761L, 2538554L, 2538556L, 2538560L, 2538563L, 2538565L, 2538570L, 2538580L, 2538582L, 2538583L, 2538736L, 2538745L, 2538751L, 2538838L, 2538839L, 2538889L, 2538891L, 2538893L, 2538898L, 2538993L, 2653502L, 2653510L, 2657141L, 2657147L, 2657830L, 2657951L, 2658379L, 198131L, 2154007L, 2156880L, 2245222L, 2808838L, 2808845L, 2808847L, 2808849L, 2808850L, 2808863L, 2808864L, 2808874L, 2808877L, 2808892L, 2808894L, 2808896L, 2808900L, 2808916L, 2808919L, 2808929L, 2808995L, 2808999L, 2809002L, 2809015L, 2809019L, 2809023L, 2815359L, 744214L, 2775461L, 2775472L, 2775476L, 2775488L, 2775490L, 2775500L, 2775502L, 2775511L, 2775514L, 2775517L, 2775520L, 2775525L, 2775527L, 2775529L, 2775540L, 2775548L, 2775551L, 2775552L, 2775554L, 2775555L, 2775557L, 2775558L, 2775560L, 2775569L, 2775576L, 2775582L, 2775583L, 2775595L, 2775616L, 1688099L, 1778583L, 1973932L, 2241468L, 2411235L, 2806479L, 2806481L, 2806484L, 2806485L, 2806487L, 2806489L, 2806490L, 2806491L, 2806492L, 2806495L, 2806498L, 2806508L, 2806509L, 2806511L, 2806515L, 2806516L, 2806518L, 2806522L, 2806531L, 2806534L, 2806538L, 2806539L, 2806540L, 2806544L, 2806547L, 2806548L, 2806549L, 2806550L, 2806551L, 2806555L, 2806563L, 2806565L, 2806567L, 2806568L, 2806569L, 2806573L, 2806576L, 2806577L, 2806579L, 2806580L, 2806581L, 2806583L, 2806584L, 2815426L, 2815438L, 2848373L, 2851052L, 2851091L, 2851127L, 2851134L, 2851136L, 2851789L, 2851809L, 2851843L, 2852006L, 357220L, 813864L, 1032735L, 1215293L, 1237010L, 1244511L, 2054369L, 2068216L, 2137331L, 2152781L, 2185651L, 2667852L, 2678981L, 2806625L, 2806627L, 2806634L, 2806638L, 2806645L, 2806646L, 2806650L, 2806651L, 2806652L, 2806663L, 2806664L, 2806665L, 2806666L, 2806670L, 2806671L, 2806672L, 2806673L, 2806677L, 2844125L, 2851809L, 633974L, 654133L, 937595L, 1005171L, 1171539L, 1274405L, 1274425L, 2033469L, 2042724L, 2195859L, 2246339L, 2668052L, 2668054L, 2668414L, 2849460L, 2849615L, 44215L, 358435L, 848224L, 940284L, 1002627L, 1334933L, 1390485L, 1395562L, 1915960L, 1922206L, 1976365L, 2051779L, 2195859L, 2278660L, 2424422L, 2480303L, 2485701L, 2850091L, 80360L, 1154126L, 1171008L, 1232388L, 1281357L, 1665301L, 1732745L, 1976128L, 2161593L, 2365655L, 2452688L, 2457511L, 2651334L, 2651341L, 2651347L, 2651351L, 2651354L, 2651370L, 2651375L, 2651377L, 2651435L, 2651528L, 2651529L, 2651530L, 2651535L, 2651596L, 2651633L, 2651641L, 2651643L, 2805680L, 2805681L, 2805682L, 2805688L, 2805759L, 2806216L, 2806221L, 2806240L, 2815028L, 2815456L, 6648L, 6866L, 8196L, 8814L, 9129L, 11943L, 16784L, 16938L, 20934L, 24522L, 45612L, 47769L, 130671L, 143926L, 275612L, 313508L, 335583L, 393725L, 473353L, 525111L, 610014L, 644537L, 660141L, 666074L, 684475L, 722113L, 750905L, 799766L, 896430L, 1026747L, 1071064L, 1109611L, 1113896L, 1170596L, 1232660L, 1290775L, 1291269L, 1382842L, 1441206L, 1460999L, 1632073L, 1793344L, 1897609L, 1993849L, 2110101L, 2133432L, 2256482L, 2443061L, 2465014L, 2489283L, 2636200L, 2673623L, 2808427L, 2904965L, 15669L, 274459L, 275680L, 571466L, 691458L, 1043250L, 1078050L, 1103920L, 1282938L, 1361299L, 1783994L, 1889525L, 2501600L, 2632592L, 2632629L, 2634435L, 2660918L, 2835717L, 2835896L, 2873563L, 2873646L, 2873679L, 2873680L, 2873681L, 2873683L, 2873684L, 2873709L, 2873711L, 2873712L, 2873714L, 2873715L, 2873716L, 2873717L, 2873718L, 12138L, 1349618L, 2415729L, 2416023L, 2416099L, 2416102L, 2657993L, 2904303L, 2904641L, 2905315L, 2913815L, 2914229L).map(Id[NormalizedURI])
    val fakeRecosFut = Future.successful(Random.shuffle(magicRecosUriIds).take(10).map { uriId =>
      RecoInfo(
        userId = None,
        uriId = uriId,
        score = 42.0f,
        explain = None,
        attribution = None)
    })

    val uriRecosFut = fakeRecosFut.flatMap { recos =>
      val recosWithUris: Seq[(RecoInfo, NormalizedURI)] = db.readOnlyReplica { implicit session =>
        recos.map { reco => (reco, nUriRepo.get(reco.uriId)) }
      }
      Future.sequence(recosWithUris.map {
        case (reco, nUri) => uriSummaryCommander.getDefaultURISummary(nUri, waiting = false).map { uriSummary =>
          val itemInfo = constructRecoItemInfo(nUri, uriSummary, reco)
          FullRecoInfo(
            kind = RecoKind.Keep,
            metaData = None,
            itemInfo = itemInfo
          )
        }
      })
    }

    if (userExperimentCommander.userHasExperiment(userId, ExperimentType.LIBRARIES)) {
      for (uriRecos <- uriRecosFut; libRecos <- topPublicLibraryRecos(userId)) yield libRecos ++ uriRecos
    } else {
      uriRecosFut
    }

  }

  def topPublicLibraryRecos(userId: Id[User]): Future[Seq[FullRecoInfo]] = {
    val curatedLibs: Seq[Id[Library]] = Seq(
      25537L, 25116L, 24542L, 25345L, 25471L, 25381L, 24203L, 25370L, 25388L, 25528L, 25371L, 25350L, 25340L, 25000L, 26106L
    ).map(Id[Library])

    Future.sequence(db.readOnlyReplica { implicit session =>
      curatedLibs.map(libRepo.get)
    }.map { lib =>
      libCommander.createFullLibraryInfo(Some(userId), lib).map(lib.ownerId -> _)
    }).map {
      libInfosWithOwner =>
        libInfosWithOwner.map {
          case (ownerId, libInfo) =>
            val item = LibRecoItemInfo(
              id = libInfo.id,
              name = libInfo.name,
              url = libInfo.url,
              description = libInfo.description,
              owner = db.readOnlyReplica { implicit session => basicUserRepo.load(ownerId) },
              followers = libInfo.followers,
              numFollowers = libInfo.numFollowers,
              numKeeps = libInfo.numKeeps)

            FullRecoInfo(
              kind = RecoKind.Library,
              metaData = None,
              itemInfo = item
            )
        }
    }
  }

}
