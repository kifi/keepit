package com.keepit.commanders.gen

import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.util.{ BatchComputable, BatchFetchable }
import com.keepit.common.util.BatchFetchable.Values

class BasicULOBatchFetcher @Inject() (
    db: Database,
    basicUserGen: BasicUserRepo,
    basicLibGen: BasicLibraryGen,
    basicOrgGen: BasicOrganizationGen) {

  def run[T](bf: BatchFetchable[T]): T = {
    bf.f(fetch(bf))
  }

  def fetch(bf: BatchFetchable[_]): Values = {
    db.readOnlyMaster { implicit s =>
      val users = basicUserGen.loadAllActive(bf.keys.users)
      val libs = basicLibGen.getBasicLibraries(bf.keys.libraries)
      val orgs = basicOrgGen.getBasicOrganizations(bf.keys.orgs)
      Values(users, libs, orgs)
    }
  }
  def compute[I, O](input: I)(tbf: I => BatchFetchable[O]): BatchComputable[I, O] = {
    BatchComputable[I, O](input, fetch(tbf(input)))
  }

  // NB: it is probably a bad sign if you're using this function
  def runInPlace[T](bf: BatchFetchable[T])(implicit session: RSession): T = {
    val users = basicUserGen.loadAllActive(bf.keys.users)
    val libs = basicLibGen.getBasicLibraries(bf.keys.libraries)
    val orgs = basicOrgGen.getBasicOrganizations(bf.keys.orgs)

    bf.f(Values(users, libs, orgs))
  }
}
